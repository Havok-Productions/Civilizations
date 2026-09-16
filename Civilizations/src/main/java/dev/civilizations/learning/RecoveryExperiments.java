package dev.civilizations.learning;

import com.google.gson.Gson;
import dev.civilizations.ai.*;
import dev.coreai.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * One live skill pilot across the server. Models propose; owning-region executors supply outcomes.
 */
public final class RecoveryExperiments implements AutoCloseable {
  public static final class Trial {
    public final String id = UUID.randomUUID().toString(), worker, village;
    public final SkillContext context;
    public final CompletableFuture<SkillProgram> program = new CompletableFuture<>();
    public volatile String teacher = "unknown";
    private volatile SkillProgram source;
    private volatile SkillContext sourceContext;
    private final Set<String> attempted = new HashSet<>();

    private Trial(String village, String worker, SkillContext context) {
      this.village = village;
      this.worker = worker;
      this.context = context;
      this.sourceContext = context;
    }
  }

  private record Proposed(SkillProgram program, String teacher) {}

  private final InferenceQueue inference;
  private final Supplier<String> teacher;
  private final SkillLibrary library;
  private final SkillLibrary verifiedContexts;
  private final TerrainRuleBook rules;

  public TerrainRuleBook rules() {
    return rules;
  }

  private final DataJournal journal;
  private final ThreadPoolExecutor io;
  private final Consumer<String> warning;
  private final AtomicReference<Trial> active = new AtomicReference<>();
  private final AtomicLong nextRequest = new AtomicLong();
  private volatile boolean closed;

  public RecoveryExperiments(
      Path root, InferenceQueue inference, Supplier<String> teacher, Consumer<String> warning)
      throws IOException {
    this.inference = inference;
    this.teacher = teacher;
    this.warning = warning;
    library = new SkillLibrary(root.resolve("skills"));
    verifiedContexts = new SkillLibrary(root.resolve("skills/verified-contexts"));
    rules = new TerrainRuleBook(root.resolve("rules"));
    journal = new DataJournal(root.resolve("data"));
    io =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32),
            r -> {
              Thread t = new Thread(r, "CoreAI-live-skills");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  private boolean enqueue(Runnable task) {
    try {
      io.execute(
          () -> {
            try {
              task.run();
            } catch (Exception error) {
              warning.accept("Live experiment IO: " + error);
            }
          });
      return true;
    } catch (RejectedExecutionException error) {
      warning.accept("Live experiment queue unavailable");
      return false;
    }
  }

  public Trial request(String village, String worker, SkillContext context, long now) {
    if (closed || now < nextRequest.get()) return null;
    Trial trial = new Trial(village, worker, context);
    if (!active.compareAndSet(null, trial)) return null;
    nextRequest.set(now + 30_000);
    if (!enqueue(
        () -> {
          try {
            if (active.get() != trial || closed) return;
            var experience = verifiedContexts.reusableExperience(context.reuseKey());
            if (experience == null) experience = library.reusableExperience(context.key());
            SkillProgram remembered =
                experience == null ? null : SkillProgram.parse(experience.source());
            if (remembered != null
                && !verifiedContexts.repeatedFailure(context.reuseKey(), remembered)
                && !library.repeatedFailure(context.key(), remembered)) {
              trial.teacher = experience.teacher();
              trial.source = remembered;
              record(
                  trial,
                  "reuse",
                  Map.of(
                      "program",
                      remembered,
                      "basis",
                      "verified physical context",
                      "successes",
                      experience.successes(),
                      "teacher",
                      trial.teacher));
              trial.program.complete(remembered);
              return;
            }
            Map<String, Object> report = new LinkedHashMap<>(context.observation());
            SkillLibrary.Experience previous = library.get(context.key());
            if (previous == null) previous = verifiedContexts.get(context.reuseKey());
            if (previous != null) report.put("previous_live_attempt", previous);
            record(trial, "requested", Map.of("observation", report));
            var inferenceFailure =
                new AtomicReference<dev.coreai.reasoning.InferenceScheduler.Failure>();
            boolean accepted =
                inference.submit(
                    "skill-trial",
                    SYSTEM,
                    new Gson().toJson(report),
                    ReasoningMode.RECOVERY,
                    SCHEMA,
                    now + 180_000,
                    text -> {
                      try {
                        return new Proposed(SkillProgram.parse(text), teacher.get());
                      } catch (RuntimeException error) {
                        String source =
                            text == null ? "" : text.substring(0, Math.min(16000, text.length()));
                        enqueue(
                            () ->
                                record(
                                    trial,
                                    "invalid_program",
                                    Map.of(
                                        "source", source, "validation_error", error.toString())));
                        throw error;
                      }
                    },
                    proposal ->
                        enqueue(
                            () -> {
                              if (active.get() != trial) return;
                              if (proposal == null) {
                                reject(trial, "inference_failed: " + inferenceFailure.get());
                                return;
                              }
                              SkillProgram program = proposal.program();
                              if (library.repeatedFailure(context.key(), program)
                                  || verifiedContexts.repeatedFailure(
                                      context.reuseKey(), program)) {
                                var repair = new LinkedHashMap<String, Object>(report);
                                repair.put("unexecuted_duplicate", program);
                                repair.put(
                                    "revision_feedback",
                                    "The proposed instructions already failed in this unchanged"
                                        + " context. Use previous_live_attempt evidence to change"
                                        + " the approach or its prerequisites.");
                                record(trial, "same_failed_program_correction_requested", repair);
                                submitRevision(
                                    trial,
                                    context,
                                    repair,
                                    System.currentTimeMillis(),
                                    trial.program,
                                    true);
                                trial.program.whenComplete(
                                    (value, error) -> {
                                      if (error != null) active.compareAndSet(trial, null);
                                    });
                                return;
                              }
                              trial.teacher = proposal.teacher();
                              trial.source = program;
                              record(
                                  trial,
                                  "proposed",
                                  Map.of(
                                      "program",
                                      program,
                                      "teacher",
                                      trial.teacher,
                                      "status",
                                      "live pilot permitted; no replay-improvement gate"));
                              trial.program.complete(program);
                            }),
                    inferenceFailure::set);
            if (!accepted) reject(trial, "inference_not_admitted: " + inferenceFailure.get());
          } catch (Exception error) {
            reject(trial, "proposal_error: " + error);
          }
        })) {
      active.compareAndSet(trial, null);
      return null;
    }
    return trial;
  }

  private void reject(Trial trial, String reason) {
    record(trial, "rejected", Map.of("reason", reason));
    trial.program.completeExceptionally(new IllegalStateException(reason));
    active.compareAndSet(trial, null);
  }

  public void step(Trial trial, Map<String, ?> evidence) {
    String json = new Gson().toJson(evidence);
    enqueue(
        () -> record(trial, "step", Map.of("evidence", new Gson().fromJson(json, Object.class))));
  }

  /**
   * Revise a stalled program using a fresh map while retaining the same worker and original goal.
   */
  public CompletableFuture<SkillProgram> revise(
      Trial trial, SkillContext fresh, Map<String, ?> failure, long now) {
    var result = new CompletableFuture<SkillProgram>();
    String evidence = new Gson().toJson(failure);
    if (!enqueue(
        () -> {
          if (closed || active.get() != trial) {
            result.completeExceptionally(new CancellationException("trial_no_longer_active"));
            return;
          }
          var report = new LinkedHashMap<String, Object>(fresh.observation());
          report.put("failed_execution", new Gson().fromJson(evidence, Object.class));
          report.put("previous_program", trial.source);
          report.put(
              "instruction",
              "Repair the failed approach using these fresh observations. Coordinates now use the"
                  + " new feet origin. Preserve the original goal. Clear accessible obstructions or"
                  + " place available support before trying a blocked WALK. Returning the same"
                  + " instructions is not a new attempt.");
          if (trial.source != null) {
            trial.attempted.add(attemptKey(trial.sourceContext, trial.source));
            try {
              library.outcome(
                  trial.sourceContext.key(), trial.source, trial.teacher, false, evidence, now);
              verifiedContexts.outcome(
                  trial.sourceContext.reuseKey(),
                  trial.source,
                  trial.teacher,
                  false,
                  evidence,
                  now);
            } catch (IOException error) {
              warning.accept("Recovery revision evidence: " + error);
            }
          }
          trial.source = null; // The abandoned source already received its failure outcome.
          record(trial, "revision_requested", report);
          submitRevision(trial, fresh, report, now, result, false);
        })) result.completeExceptionally(new IllegalStateException("revision_io_unavailable"));
    return result;
  }

  /** A duplicate receives concrete feedback once before the unchanged proposal is deferred. */
  private void submitRevision(
      Trial trial,
      SkillContext fresh,
      Map<String, Object> report,
      long now,
      CompletableFuture<SkillProgram> result,
      boolean corrected) {
    var inferenceFailure = new AtomicReference<dev.coreai.reasoning.InferenceScheduler.Failure>();
    boolean accepted =
        inference.submit(
            "skill-revision:" + trial.id,
            SYSTEM,
            new Gson().toJson(report),
            ReasoningMode.RECOVERY,
            SCHEMA,
            now + 180_000,
            text -> new Proposed(SkillProgram.parse(text), teacher.get()),
            proposal -> {
              if (!enqueue(
                  () -> {
                    if (closed || active.get() != trial) {
                      result.completeExceptionally(
                          new CancellationException("trial_no_longer_active"));
                      return;
                    }
                    boolean duplicate =
                        proposal != null
                            && (trial.attempted.contains(attemptKey(fresh, proposal.program()))
                                || library.repeatedFailure(fresh.key(), proposal.program())
                                || verifiedContexts.repeatedFailure(
                                    fresh.reuseKey(), proposal.program()));
                    if (duplicate && !corrected) {
                      var repair = new LinkedHashMap<String, Object>(report);
                      repair.put("unexecuted_duplicate", proposal.program());
                      repair.put(
                          "revision_feedback",
                          "These exact instructions already failed with this terrain, position,"
                              + " goal and inventory. Change the failed action or its"
                              + " prerequisites, not only the explanation. Use the supplied"
                              + " obstruction evidence to choose a different reachable approach,"
                              + " clearance or support.");
                      record(trial, "revision_correction_requested", repair);
                      submitRevision(
                          trial, fresh, repair, System.currentTimeMillis(), result, true);
                      return;
                    }
                    if (proposal == null || duplicate) {
                      String reason =
                          proposal == null
                              ? "revision_unavailable: " + inferenceFailure.get()
                              : "unchanged_failed_recovery_instructions";
                      record(
                          trial,
                          "revision_rejected",
                          Map.of(
                              "reason",
                              reason,
                              "observation",
                              fresh.observation(),
                              "unexecuted_program",
                              proposal == null ? "none" : proposal.program()));
                      result.completeExceptionally(new IllegalArgumentException(reason));
                      return;
                    }
                    trial.source = proposal.program();
                    trial.sourceContext = fresh;
                    trial.teacher = proposal.teacher();
                    record(
                        trial,
                        "revision_proposed",
                        Map.of(
                            "program",
                            trial.source,
                            "observation",
                            fresh.observation(),
                            "teacher",
                            trial.teacher));
                    result.complete(trial.source);
                  }))
                result.completeExceptionally(new IllegalStateException("revision_io_unavailable"));
            },
            inferenceFailure::set);
    if (!accepted)
      result.completeExceptionally(
          new IllegalStateException("revision_not_admitted: " + inferenceFailure.get()));
  }

  private static String attemptKey(SkillContext context, SkillProgram program) {
    return context.map().fingerprint
        + ":"
        + context.origin()
        + ":"
        + context.goal()
        + ":"
        + context.reach2()
        + ":"
        + new TreeMap<>(context.inventory())
        + ":"
        + new Gson().toJson(program.steps());
  }

  public void finish(Trial trial, boolean success, String reason, Map<String, ?> evidence) {
    if (!active.compareAndSet(trial, null)) return;
    String json = new Gson().toJson(evidence);
    trial.program.completeExceptionally(new CancellationException(reason));
    enqueue(
        () -> {
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("success", success);
          try {
            var savedRules = rules.finish(trial.id, success);
            result.put(
                "rule_state",
                Map.of(
                    "classifications",
                    savedRules.rules().size(),
                    "search_radius",
                    savedRules.searchRadius(),
                    "parameters",
                    savedRules.parameters()));
          } catch (IOException error) {
            result.put("rule_persistence_error", error.toString());
            warning.accept("Rule learning not persisted: " + error);
          }
          result.put("basis", "executor observation");
          result.put("observation", trial.context.observation());
          result.put("program_observation", trial.sourceContext.observation());
          if (trial.source != null) result.put("program", trial.source);
          result.put("teacher", trial.teacher);
          result.put("reason", reason);
          result.put("evidence", new Gson().fromJson(json, Object.class));
          try {
            if (trial.source != null)
              result.put(
                  "learning",
                  library.outcome(
                      trial.sourceContext.key(),
                      trial.source,
                      trial.teacher,
                      success,
                      json,
                      System.currentTimeMillis()));
            if (trial.source != null)
              verifiedContexts.outcome(
                  trial.sourceContext.reuseKey(),
                  trial.source,
                  trial.teacher,
                  success,
                  json,
                  System.currentTimeMillis());
          } catch (IOException error) {
            result.put("persistence_error", error.toString());
            warning.accept("Skill learning not persisted: " + error);
          }
          record(trial, "outcome", result);
        });
  }

  public void cancelWorker(String worker, String reason) {
    Trial t = active.get();
    if (t != null && t.worker.equals(worker)) cancel(t, reason);
  }

  public void cancel(Trial trial, String reason) {
    if (!active.compareAndSet(trial, null)) return;
    trial.program.completeExceptionally(new CancellationException(reason));
    enqueue(
        () -> {
          try {
            rules.finish(trial.id, false);
          } catch (IOException error) {
            warning.accept("Interrupted trial facts not persisted: " + error);
          }
          record(
              trial,
              "cancelled",
              Map.of(
                  "reason",
                  reason,
                  "basis",
                  "interrupted; verified classifications retained, behavior edits discarded"));
        });
  }

  private void record(Trial trial, String event, Map<String, ?> data) {
    try {
      journal.append(
          "experiments",
          Map.of(
              "schema",
              1,
              "time",
              System.currentTimeMillis(),
              "trial",
              trial.id,
              "village",
              trial.village,
              "worker",
              trial.worker,
              "context",
              trial.context.key(),
              "event",
              event,
              "data",
              data));
    } catch (IOException error) {
      warning.accept("Experiment journal: " + error);
    }
  }

  public String status() {
    Trial t = active.get();
    var learned = rules.snapshot();
    return (t == null ? "idle" : "trial=" + t.id + ", worker=" + t.worker)
        + "; learned classifications="
        + learned.rules().size()
        + "; tuned parameters="
        + learned.parameters()
        + "; learned search radius="
        + (learned.searchRadius() == 0 ? "configured default" : learned.searchRadius());
  }

  public void close() {
    closed = true;
    Trial t = active.get();
    if (t != null) cancel(t, "server_shutdown");
    io.shutdown();
  }

  private static final String SYSTEM =
      """
      You are a Minecraft villager's local skill programmer. A real route attempt failed. Write a small executable recovery program, not advice or a claim of success. You may CLASSIFY observed blocks using supplied physical_block_probes, SEARCH with a new radius, WALK to observed relative feet coordinates, CLEAR natural dirt/trees or classified removable clutter, PLACE_SUPPORT using actual available inventory to make a short step or dry crossing, then VERIFY. You may detour away from the goal first. Try a different approach from a previous failed attempt and use its actual evidence. Do not repeat unchanged failed instructions. Use relative coordinates from the supplied origin. The host observes instruction areas as needed and WALK travels through successive local maps, clearing feasible natural obstacles along the way. Unavailable space is unknown, not air; observation failures are reported for replanning. Walk before actions that are out of reach. Do not remove buildings or player-protected blocks, enter hazards or create unsupported falling blocks. CLASSIFY is an executable rewrite of your terrain rule table. Its x/y/z select an observed block relative to origin, material is PASSABLE, CLEARABLE or OBSTACLE. Physical collision, fluid, damage and nonstructural-removal probes test the proposed classification. Prefer PASSABLE for harmless non-colliding ground cover; CLEARABLE for removable obstacles that actually block the goal. An unknown/unobserved chunk is not an unknown block type and cannot be made into air. A classification that matches a live physical probe is saved independently even if a later route instruction fails. Search and parameter edits remain trial-scoped until verified goal completion. SEARCH uses x=desired horizontal radius, y=z=0,material="". It rewrites search radius for this trial; VERIFY requests a new map and native route under your changes. Use it when a detour exceeds the present map, or reduce it if search work is excessive. Example: SEARCH radius32 followed by VERIFY, with no walking instruction required. Do not use bigger distances without a reason in the map/failure evidence. Existing successful rules and verified programs are reused locally. WALK uses normal native movement; the host may permit a short exit from existing shallow water to dry land. PLACE_SUPPORT consumes one named inventory item, requires air, dry nearby support and clear space above, and can never replace a block. CLEAR retains actual drops. Proposals have no configured numeric ranges. Use finite instructions; execution obtains observations and reports actual world or resource constraints instead of silently clamping a proposed value. End with VERIFY x=0,y=0,z=0,material=""; the host checks arrival at the ORIGINAL goal after continuing normal navigation if needed. If verification_goal is CLEAR_SITE, the original target block must actually become AIR; arrival does not count, and you can finish CLEAR then VERIFY without moving. VERIFY is never a model assertion of success. TUNE edits named controller parameters from tunable_parameters: material=key,x=integer value,y=z=0. Explain which observed failure each change addresses. Current values, units and purposes are supplied without allowed ranges; keys identify implemented consumers. Parameter changes first apply to this worker, persist only after verified completion, and can be revised in later trials. Do not change timing without evidence, or claim a speed improvement merely because a task completes. Other instructions except CLASSIFY, TUNE and PLACE_SUPPORT use material="".  Return only the JSON program: explanation and steps [{op,x,y,z,material}].
      """;
  public static final String SCHEMA = SkillProgram.schema();
}
