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

    private Trial(String village, String worker, SkillContext context) {
      this.village = village;
      this.worker = worker;
      this.context = context;
    }
  }

  private record Proposed(SkillProgram program, String teacher) {}

  private final InferenceQueue inference;
  private final Supplier<String> teacher;
  private final SkillLibrary library;
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
            SkillProgram remembered = library.reusable(context.key());
            if (remembered != null) {
              trial.teacher = library.get(context.key()).teacher();
              trial.source = remembered;
              record(trial, "reuse", Map.of("program", remembered));
              trial.program.complete(remembered);
              return;
            }
            Map<String, Object> report = new LinkedHashMap<>(context.observation());
            SkillLibrary.Experience previous = library.get(context.key());
            if (previous != null) report.put("previous_live_attempt", previous);
            record(trial, "requested", Map.of("observation", report));
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
                                reject(trial, "model_unavailable_or_invalid_program");
                                return;
                              }
                              SkillProgram program = proposal.program();
                              if (library.repeatedFailure(context.key(), program)) {
                                reject(trial, "same_failed_program_for_unchanged_context");
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
                            }));
            if (!accepted) reject(trial, "inference_queue_busy");
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
          if (trial.source != null) result.put("program", trial.source);
          result.put("teacher", trial.teacher);
          result.put("reason", reason);
          result.put("evidence", new Gson().fromJson(json, Object.class));
          try {
            if (trial.source != null)
              result.put(
                  "learning",
                  library.outcome(
                      trial.context.key(),
                      trial.source,
                      trial.teacher,
                      success,
                      json,
                      System.currentTimeMillis()));
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
    rules.cancel(trial.id);
    if (!active.compareAndSet(trial, null)) return;
    trial.program.completeExceptionally(new CancellationException(reason));
    enqueue(
        () ->
            record(
                trial,
                "cancelled",
                Map.of(
                    "reason",
                    reason,
                    "basis",
                    "interrupted; neither success nor learned failure")));
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
      You are a Minecraft villager's local skill programmer. A real route attempt failed. Write a small executable recovery program, not advice or a claim of success. You may CLASSIFY observed blocks using supplied physical_block_probes, SEARCH with a new radius, WALK to observed relative feet coordinates, CLEAR natural dirt/trees or classified removable clutter, PLACE_SUPPORT using actual available inventory to make a short step or dry crossing, then VERIFY. You may detour away from the goal first. Try a different approach from a previous failed attempt and use its actual evidence. Do not repeat unchanged failed instructions. Use the supplied map and relative coordinates; unavailable space is unknown, not air. Walk before actions that are out of reach. Do not remove buildings or player-protected blocks, enter hazards or create unsupported falling blocks. CLASSIFY is an executable rewrite of your terrain rule table. Its x/y/z select an observed block relative to origin, material is PASSABLE, CLEARABLE or OBSTACLE. Physical collision, fluid, damage and nonstructural-removal probes test the proposed classification. Prefer PASSABLE for harmless non-colliding ground cover; CLEARABLE for removable obstacles that actually block the goal. An unknown/unobserved chunk is not an unknown block type and cannot be made into air. A classification is trial-scoped and saved only after observed goal arrival. SEARCH uses x=desired horizontal radius (8..max_adaptive_search_radius), y=z=0,material="". It rewrites search radius for this trial; VERIFY requests a new map and native route under your changes. Use it when a detour exceeds the present map, or reduce it if search work is excessive. Example: SEARCH radius32 followed by VERIFY, with no walking instruction required. Do not use bigger distances without a reason in the map/failure evidence. Existing successful rules and verified programs are reused locally. WALK uses normal native movement; the host may permit a short exit from existing shallow water to dry land. PLACE_SUPPORT consumes one named inventory item, requires air, dry nearby support and clear space above, and can never replace a block. CLEAR retains actual drops. Limit 24 instructions, 8 clears and 4 placements, coordinates within x/z +/-20 and y +/-10. End with VERIFY x=0,y=0,z=0,material=""; the host checks arrival at the ORIGINAL goal after continuing normal navigation if needed. If verification_goal is CLEAR_SITE, the original target block must actually become AIR; arrival does not count, and you can finish CLEAR then VERIFY without moving. VERIFY is never a model assertion of success. TUNE edits named controller parameters from tunable_parameters: material=key,x=integer value,y=z=0. Use at most three TUNE instructions and explain which observed failure the change addresses. Current values, units and allowed ranges are supplied; unknown keys are rejected. Parameter changes first apply to this worker, persist only after verified completion, and can be revised in later trials. Do not change timing without evidence, or claim a speed improvement merely because a task completes. Other instructions except CLASSIFY, TUNE and PLACE_SUPPORT use material="". At most eight CLASSIFY and one SEARCH per program. Return only the JSON program: explanation and steps [{op,x,y,z,material}].
      """;
  public static final String SCHEMA = SkillProgram.schema();
}
