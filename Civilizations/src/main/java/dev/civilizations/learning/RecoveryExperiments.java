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
    private final RecoveryReadLedger reads = new RecoveryReadLedger();

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
  private final RecoveryDiagnostics diagnostics;

  public TerrainRuleBook rules() {
    return rules;
  }

  private final DataJournal journal;
  private final ThreadPoolExecutor io;
  private final Consumer<String> warning;
  private final AtomicReference<Trial> active = new AtomicReference<>();
  private final AtomicLong nextRequest = new AtomicLong();
  private final AtomicBoolean factsQueued = new AtomicBoolean();
  private volatile long nextFactsRetry;
  private volatile boolean closed;

  public RecoveryExperiments(
      Path root, InferenceQueue inference, Supplier<String> teacher, Consumer<String> warning)
      throws IOException {
    this.inference = inference;
    this.teacher = teacher;
    this.warning = warning;
    diagnostics = new RecoveryDiagnostics(root);
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
    enqueue(
        () -> {
          try {
            int restored = LessonRecovery.restore(root, rules);
            if (restored > 0)
              journal.append(
                  "lessons",
                  Map.of(
                      "time",
                      System.currentTimeMillis(),
                      "event",
                      "historical_facts_restored",
                      "count",
                      restored));
          } catch (IOException error) {
            warning.accept("Historical block lessons could not be restored: " + error);
          }
        });
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

  /** Live physical facts require no teacher request and no successful route to be remembered. */
  public synchronized boolean remember(
      TerrainRuleBook.Facts facts, String category, String source, String provenance) {
    if (closed) return false;
    boolean changed = rules.learn(facts, category, source, provenance);
    flushFacts();
    return changed;
  }

  private void flushFacts() {
    if (System.currentTimeMillis() < nextFactsRetry
        || !rules.dirty()
        || !factsQueued.compareAndSet(false, true)) return;
    if (!enqueue(
        () -> {
          boolean saved = false;
          try {
            var state = rules.flush();
            journal.append(
                "lessons",
                Map.of(
                    "time",
                    System.currentTimeMillis(),
                    "event",
                    "facts_saved",
                    "classifications",
                    state.rules().size(),
                    "basis",
                    "verified physical observations"));
            saved = true;
          } catch (IOException error) {
            nextFactsRetry = System.currentTimeMillis() + 5000;
            warning.accept("Block lessons not persisted; retained for retry: " + error);
          } finally {
            factsQueued.set(false);
            if (saved && !closed) flushFacts();
          }
        })) factsQueued.set(false);
  }

  public void saveLessons() {
    if (!closed) flushFacts();
  }

  public Map<String, Object> diagnosticReport(String village) {
    return diagnostics.report(village);
  }

  /**
   * World threads submit immutable receipts; persistence and proposal generation happen off tick.
   */
  public void diagnose(String village, String subject, String reason, Map<String, ?> evidence) {
    String snapshot = new Gson().toJson(diagnosticEvidence(evidence));
    enqueue(
        () -> {
          try {
            diagnostics.record(
                village,
                subject,
                reason,
                new Gson().fromJson(snapshot, Map.class),
                System.currentTimeMillis());
          } catch (IOException error) {
            warning.accept("Recovery diagnosis persistence: " + error);
          }
        });
  }

  private static Map<String, Object> diagnosticEvidence(Map<String, ?> evidence) {
    var compact = new LinkedHashMap<String, Object>();
    for (String key :
        List.of(
            "reason",
            "position",
            "origin",
            "goal",
            "original_goal",
            "target",
            "inventory",
            "instruction",
            "failed_index",
            "verified_steps",
            "resource",
            "job",
            "mode",
            "final_instruction",
            "placed",
            "cleared",
            "instructions_completed",
            "interruption")) if (evidence.get(key) != null) compact.put(key, evidence.get(key));
    return compact;
  }

  private void diagnose(Trial trial, String reason, Map<String, ?> evidence) {
    var receipt = diagnosticEvidence(evidence);
    receipt.put("goal", trial.context.goal());
    receipt.put("origin", trial.context.origin());
    receipt.put("verified_steps", trial.reads.verified());
    try {
      diagnostics.record(
          trial.village,
          "goal:" + trial.context.goal().key(),
          reason,
          receipt,
          System.currentTimeMillis());
    } catch (IOException error) {
      warning.accept("Recovery diagnosis persistence: " + error);
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
            report.put("diagnostic_memory", diagnostics.report(village));
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
    diagnose(trial, reason, Map.of());
    record(trial, "rejected", Map.of("reason", reason));
    trial.program.completeExceptionally(new IllegalStateException(reason));
    active.compareAndSet(trial, null);
  }

  public void step(Trial trial, Map<String, ?> evidence) {
    String json = new Gson().toJson(evidence);
    enqueue(
        () -> {
          Map<String, ?> receipt = new Gson().fromJson(json, Map.class);
          trial.reads.step(receipt);
          record(trial, "step", Map.of("evidence", receipt));
        });
  }

  /**
   * Revise a stalled program using a fresh map while retaining the same worker and original goal.
   */
  public CompletableFuture<SkillProgram> revise(
      Trial trial, SkillContext fresh, Map<String, ?> failure, long now) {
    return revise(trial, fresh, failure, now, false);
  }

  /**
   * Requested observations are successful reads, not evidence that the previous behavior failed.
   */
  public CompletableFuture<SkillProgram> revise(
      Trial trial, SkillContext fresh, Map<String, ?> failure, long now, boolean observationOnly) {
    var result = new CompletableFuture<SkillProgram>();
    String evidence = new Gson().toJson(failure);
    if (!enqueue(
        () -> {
          if (closed || active.get() != trial) {
            result.completeExceptionally(new CancellationException("trial_no_longer_active"));
            return;
          }
          var report = new LinkedHashMap<String, Object>(fresh.observation());
          if (observationOnly) trial.reads.observed(fresh, failure);
          else diagnose(trial, Objects.toString(failure.get("reason"), fresh.failure()), failure);
          report.put("diagnostic_memory", diagnostics.report(trial.village));
          report.put("verified_steps", trial.reads.verified());
          report.put(
              observationOnly ? "observation_request" : "failed_execution",
              new Gson().fromJson(evidence, Object.class));
          report.put("previous_program", trial.source);
          report.put(
              "instruction",
              observationOnly
                  ? "The requested read completed. Use its fresh observations to continue the"
                      + " original goal. Coordinates use the current feet origin. Remaining old"
                      + " instructions were replaced, not executed."
                  : "Repair the failed approach using these fresh observations. Coordinates now use"
                      + " the new feet origin. Preserve the original goal. Clear accessible"
                      + " obstructions or place available support before trying a blocked WALK."
                      + " Returning the same instructions is not a new attempt.");
          if (trial.source != null && !observationOnly) {
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
          trial.source =
              null; // Observation requests receive neither goal success nor a behavior failure.
          record(trial, observationOnly ? "observation_requested" : "revision_requested", report);
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
                    String repeatedRead =
                        proposal == null ? "" : trial.reads.repeatedRead(fresh, proposal.program());
                    boolean duplicate =
                        proposal != null
                            && (!repeatedRead.isEmpty()
                                || trial.attempted.contains(attemptKey(fresh, proposal.program()))
                                || library.repeatedFailure(fresh.key(), proposal.program())
                                || verifiedContexts.repeatedFailure(
                                    fresh.reuseKey(), proposal.program()));
                    if (duplicate && !corrected) {
                      if (!repeatedRead.isEmpty()) diagnose(trial, repeatedRead, report);
                      var repair = new LinkedHashMap<String, Object>(report);
                      repair.put("unexecuted_duplicate", proposal.program());
                      repair.put(
                          "revision_feedback",
                          !repeatedRead.isEmpty()
                              ? repeatedRead
                                  + ". The read already succeeded; use its result and the"
                                  + " verified_steps. Choose a different action, prerequisite or"
                                  + " genuinely unknown focus. No task completion has been"
                                  + " inferred."
                              : "These exact instructions already failed with this terrain,"
                                    + " position, goal and inventory. Change the failed action or"
                                    + " its prerequisites, not only the explanation. Use the"
                                    + " supplied obstruction evidence to choose a different"
                                    + " reachable approach, clearance or support.");
                      record(trial, "revision_correction_requested", repair);
                      submitRevision(
                          trial, fresh, repair, System.currentTimeMillis(), result, true);
                      return;
                    }
                    if (proposal == null || duplicate) {
                      String reason =
                          proposal == null
                              ? "revision_unavailable: " + inferenceFailure.get()
                              : !repeatedRead.isEmpty()
                                  ? repeatedRead
                                  : "unchanged_failed_recovery_instructions";
                      diagnose(trial, reason, report);
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
    if (!accepted) {
      diagnose(trial, "revision_not_admitted: " + inferenceFailure.get(), report);
      result.completeExceptionally(
          new IllegalStateException("revision_not_admitted: " + inferenceFailure.get()));
    }
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
          if (!success) diagnose(trial, reason, new Gson().fromJson(json, Map.class));
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
          diagnose(trial, reason, Map.of("interruption", true));
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

  public synchronized void close() {
    if (closed) return;
    closed = true;
    Trial t = active.get();
    if (t != null) cancel(t, "server_shutdown");
    // A final write runs after any in-flight snapshot, even when a coalesced flush already exists.
    enqueue(
        () -> {
          try {
            rules.flush();
            diagnostics.flush();
          } catch (IOException error) {
            warning.accept("Final block lesson save failed: " + error);
          }
        });
    io.shutdown();
    try {
      if (!io.awaitTermination(5, TimeUnit.SECONDS))
        warning.accept("Learning IO still draining during shutdown");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }

  public static final String SYSTEM =
      """
      You control a Minecraft villager recovering a real task. Return an executable JSON program, not advice or a claim of success: explanation and steps [{op,x,y,z,material}]. Coordinates are relative to the supplied current feet origin. Preserve the ORIGINAL goal. Host execution supplies outcomes; VERIFY is never a model assertion.
      Available operations:
      OBSERVE x,y,z,material="": request a fresh read of loaded terrain around that relative focus WITHOUT walking. This pauses and replaces remaining instructions with a new model request containing the result. Use it when height, clearance or distant terrain is unknown. Unknown chunks stay unknown; no forced chunk loads. Do not repeat an unchanged observation when its result already answers the question.
      CLASSIFY x,y,z,material=PASSABLE|CLEARABLE|OBSTACLE: remotely probe a loaded block's actual collision/fluid/damage/removability. No walking needed. Proven facts persist independently of route success. Classifications cannot turn unknown chunks into air or enable removal of buildings. Harmless non-colliding plants are PASSABLE and usually need no action. CLEARABLE classification requires nonsolid clutter; solid dirt cannot pass that probe. SOFT dirt/trees already have natural clearance permission: use CLEAR directly, without CLASSIFY.
      WALK x,y,z,material="": normal native movement through successive observed maps, with feasible natural clearance; no flying, teleporting or swimming into danger. An air cell is not a supported stand. A large height difference needs stairs or a measured route, not a direct jump.
      CLEAR x,y,z,material="": remove natural dirt/trees or physically classified nonstructural clutter within reach, retaining drops. Host walks into reach first. P=protected/reserved, B=structure/support cannot be cleared. Ordinary solid # is distinct: it requires an ordinary mining task and suitable tool, which CLEAR does not implement. Classification alone cannot make stone removable by CLEAR. Use a measured detour or support instead if mining is needed.
      PLACE_SUPPORT x,y,z,material=one supplied support item: consume one actual inventory block into empty dry space with an anchor and clearance above; cannot replace occupied blocks, buildings or entities. Walk into reach first. Build a reachable sequence, not floating blocks.
      SEARCH x=horizontal radius,y=z=0,material="": change the worker's map radius, then VERIFY remaps. Enlarge only when the obstacle/detour exceeds the current map; shrinking cannot solve missing observations. Vertical coverage already follows the goal's height.
      TUNE x=integer value,y=z=0,material=implemented key from tunable_parameters: revise controller behavior with a concrete reason. No configured numeric ranges; actual geometry and resource constraints are reported. SEARCH/TUNE stay trial-scoped until verified completion.
      VERIFY x=y=z=0,material="" must be last. Host resumes normal navigation and checks ARRIVAL at the original goal. For CLEAR_SITE the original block must actually be AIR, so CLEAR then VERIFY can finish without moving.
      Read the local levels, route_height vertical slice and obstacle removal reasons. Solid floor below the feet is useful support, not a body obstruction. reach_squared describes final arrival tolerance, not map radius or block interaction reach. CLEAR needs no building inventory. Slice columns are samples, not evidence that the gaps are clear. ? means unobserved. Available inventory, failed actions and fresh observations are authoritative. Change the failed action or its prerequisites rather than repeating unchanged failed instructions. Request missing observations instead of guessing. Do not enter hazards, remove protected blocks, or claim task completion before host verification.
      diagnostic_memory explains earlier host failures and interruptions. verified_steps are measured partial results, not completed goals. Use successful reads instead of asking for them again; distinguish absent executor capabilities from missing terrain. Coordinates in diagnostic receipts are absolute; instructions still use the CURRENT relative origin. A code repair proposal is recorded locally when a host defect or missing capability prevents progress.
      """;
  public static final String SCHEMA = SkillProgram.schema();
}
