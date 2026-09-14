package dev.civilizations.learning;

import com.google.gson.Gson;
import dev.civilizations.ai.*;
import dev.coreai.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** Async data collection and bounded code adaptation. No world objects cross this boundary. */
public final class CoreAiCoordinator implements AutoCloseable {
  public enum Scope {
    JOBS,
    ROUTES
  }

  public record Choice(Scope scope, String version, List<PolicyCase.Option> options) {
    public Choice {
      options = List.copyOf(options);
    }
  }

  public record Ticket(String id, String village, String worker, Choice choice, String selected) {}

  private final Map<Scope, PolicyLibrary> libraries = new EnumMap<>(Scope.class);
  private final DataJournal journal;
  private final Path knowledgeFile;
  private final InferenceQueue inference;
  private final Supplier<PolicyLibrary.Provenance> provenance;
  private final Consumer<String> warning;
  private final ThreadPoolExecutor io;
  private final ScheduledExecutorService timer;
  private final AtomicLong dropped = new AtomicLong();
  private final Set<String> finishedTickets = new LinkedHashSet<>();
  private final ArrayDeque<Map<String, ?>> recent = new ArrayDeque<>();
  private final Map<Scope, Map<String, ?>> feedback = new EnumMap<>(Scope.class);
  private final long interval;
  private long nextAttempt;
  private int failures, lastFailures, attempts;
  private long reviewGeneration;
  private volatile boolean pending;
  private volatile boolean closed;
  private volatile String last = "waiting for observed roadblocks";
  private volatile long observations, outcomes;
  private volatile boolean liveTrials;

  public void liveTrials(boolean enabled) {
    liveTrials = enabled;
  }

  public CoreAiCoordinator(
      Path root,
      InferenceQueue inference,
      Supplier<PolicyLibrary.Provenance> provenance,
      Consumer<String> warning,
      boolean adapt,
      long interval)
      throws IOException {
    this.inference = inference;
    this.provenance = provenance;
    this.warning = warning;
    this.interval = Math.max(60_000, interval);
    journal = new DataJournal(root.resolve("data"));
    Files.createDirectories(root.resolve("knowledge"));
    knowledgeFile = root.resolve("knowledge/server-recipes.json");
    libraries.put(Scope.JOBS, load(root.resolve("policies/jobs"), VillagerPolicyCases.jobs()));
    libraries.put(
        Scope.ROUTES, load(root.resolve("policies/routes"), VillagerPolicyCases.routes()));
    io =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256),
            r -> {
              Thread t = new Thread(r, "CoreAI-data");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    timer =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "CoreAI-review");
              t.setDaemon(true);
              return t;
            });
    if (adapt) timer.scheduleWithFixedDelay(() -> enqueue(this::review), 60, 60, TimeUnit.SECONDS);
  }

  private PolicyLibrary load(Path path, List<PolicyCase> guards) throws IOException {
    try {
      return new PolicyLibrary(path, guards);
    } catch (IOException invalid) {
      // Invalid host state never enables generated code. Preserve one bad copy for diagnosis.
      if (!Files.exists(path.resolve("state.json"))) throw invalid;
      Files.move(
          path.resolve("state.json"),
          path.resolve("state.invalid.json"),
          StandardCopyOption.REPLACE_EXISTING);
      warning.accept(
          "CoreAI restored baseline for " + path.getFileName() + ": " + invalid.getMessage());
      return new PolicyLibrary(path, guards);
    }
  }

  public Choice rank(Scope scope, List<PolicyCase.Option> options) {
    var ranked = libraries.get(scope).rank(options);
    return new Choice(scope, ranked.version(), ranked.options());
  }

  public Choice rank(Scope scope, List<PolicyCase.Option> options, String worker) {
    var ranked =
        liveTrials && worker != null
            ? libraries.get(scope).rankForWorker(options, worker)
            : libraries.get(scope).rank(options);
    return new Choice(scope, ranked.version(), ranked.options());
  }

  public Ticket begin(String village, String worker, Choice choice, String selected) {
    if (choice == null || choice.options.stream().noneMatch(o -> o.id().equals(selected)))
      return null;
    // A model override or a lost claim is not an outcome caused by the top-ranked policy choice.
    Choice attributed =
        choice.options.getFirst().id().equals(selected)
            ? choice
            : new Choice(choice.scope, "host-or-model-override", choice.options);
    Ticket ticket = new Ticket(UUID.randomUUID().toString(), village, worker, attributed, selected);
    enqueue(
        () -> {
          observations++;
          record(
              "observations",
              Map.of(
                  "ticket", ticket, "basis", "host observed feasible candidates; selection only"));
        });
    return ticket;
  }

  public void outcome(Ticket ticket, boolean success, Map<String, ?> evidence) {
    if (ticket == null) return;
    // Roundtrip freezes nested caller collections and limits payload before it crosses threads.
    String encoded = new Gson().toJson(evidence);
    if (encoded.length() > 32_000) encoded = "{\"error\":\"evidence size limit\"}";
    final String snapshot = encoded;
    enqueue(
        () -> {
          if (!finishedTickets.add(ticket.id)) return;
          if (finishedTickets.size() > 4096)
            finishedTickets.remove(finishedTickets.iterator().next());
          outcomes++;
          Map<String, ?> event =
              Map.of(
                  "ticket",
                  ticket,
                  "success",
                  success,
                  "evidence",
                  new Gson().fromJson(snapshot, Object.class),
                  "basis",
                  "executor observation");
          record("outcomes", event);
          if (recent.size() == 16) recent.removeFirst();
          recent.addLast(event);
          if (!success) {
            failures++;
            record(
                "roadblocks",
                Map.of(
                    "ticket",
                    ticket.id,
                    "evidence",
                    new Gson().fromJson(snapshot, Object.class),
                    "status",
                    "observed failure; cause not yet established"));
          }
          PolicyLibrary library = libraries.get(ticket.choice.scope);
          try {
            String trialResult =
                library.trialOutcome(ticket.choice.version, ticket.worker, success);
            if (!trialResult.isEmpty()) {
              last = trialResult;
              record(
                  "proposals",
                  Map.of(
                      "event", trialResult, "ticket", ticket.id, "version", ticket.choice.version));
            }
            if (library.outcome(ticket.choice.version, success)) {
              last = ticket.choice.scope + " rolled back after three consecutive observed failures";
              record(
                  "proposals",
                  Map.of("event", "rollback", "version", ticket.choice.version, "reason", last));
            }
          } catch (IOException error) {
            warning.accept("CoreAI policy persistence: " + error.getMessage());
          }
          if (success)
            library.remember(
                new PolicyCase(
                    ticket.id,
                    "completed selected action; alternatives untested",
                    ticket.choice.options,
                    ticket.selected));
        });
  }

  public void roadblock(String village, String worker, Map<String, ?> evidence) {
    String encoded = new Gson().toJson(evidence);
    if (encoded.length() > 32_000) return;
    enqueue(
        () -> {
          failures++;
          Map<String, ?> event =
              Map.of(
                  "village",
                  village,
                  "worker",
                  worker,
                  "evidence",
                  new Gson().fromJson(encoded, Object.class),
                  "status",
                  "observed failure in protected executor; cause needs diagnosis");
          record("roadblocks", event);
          if (recent.size() == 16) recent.removeFirst();
          recent.addLast(event);
        });
  }

  /** Complete immutable agent experiences; independent of policy scoring and model suggestions. */
  public void experience(dev.coreai.agent.AgentSession.Experience experience) {
    enqueue(
        () ->
            record("agents", Map.of("experience", experience, "basis", "host executor lifecycle")));
  }

  private void review() {
    long now = System.currentTimeMillis();
    if (closed || pending || now < nextAttempt || failures - lastFailures < 3 || !inference.idle())
      return;
    Scope scope = attempts / 2 % 2 == 0 ? Scope.JOBS : Scope.ROUTES;
    // Alternate teachers independently of scope: both engines see both policy types.
    ReasoningMode mode = attempts % 2 == 0 ? ReasoningMode.FINAL : ReasoningMode.RECOVERY;
    PolicyLibrary library = libraries.get(scope);
    String report =
        new Gson()
            .toJson(
                Map.of(
                    "scope",
                    scope,
                    "active_source",
                    library.version().source(),
                    "cases",
                    library.report().stream().limit(10).toList(),
                    "recent_executor_results",
                    recent.stream().skip(Math.max(0, recent.size() - 3)).toList(),
                    "last_review",
                    feedback.getOrDefault(scope, Map.of("message", last))));
    if (report.length() > 9000)
      report =
          new Gson()
              .toJson(
                  Map.of(
                      "scope",
                      scope,
                      "active_source",
                      library.version().source(),
                      "cases",
                      library.report().stream().limit(6).toList(),
                      "last_review",
                      feedback.getOrDefault(scope, Map.of("message", last))));
    pending = true;
    long reviewToken = reviewGeneration;
    boolean accepted =
        inference.submit(
            "coreai-review",
            PolicyTeacher.SYSTEM,
            report,
            mode,
            PolicyTeacher.SCHEMA,
            now + 180_000,
            text -> new PolicyTeacher.Answer(PolicyTeacher.parse(text), provenance.get()),
            answer -> {
              if (!enqueue(
                  () -> {
                    pending = false;
                    if (reviewToken != reviewGeneration) return;
                    if (answer == null) {
                      last =
                          "teacher unavailable or invalid output; baseline/accepted policy"
                              + " continues";
                      record("proposals", Map.of("event", "teacher_failed", "scope", scope));
                      return;
                    }
                    try {
                      PolicyLibrary.Evaluation evaluation =
                          liveTrials
                              ? library.stageTrial(answer.proposal().source(), answer.provenance())
                              : library.propose(answer.proposal().source(), answer.provenance());
                      last = scope + ": " + evaluation.reason();
                      feedback.put(
                          scope,
                          Map.of(
                              "previous_source", answer.proposal().source(), "result", evaluation));
                      record(
                          "proposals",
                          Map.of(
                              "scope",
                              scope,
                              "answer",
                              answer,
                              "evaluation",
                              evaluation,
                              "basis",
                              "teacher hypothesis and host replay; not a world success"));
                      if (!answer.proposal().protectedRoadblock().isBlank())
                        record(
                            "roadblocks",
                            Map.of(
                                "scope",
                                scope,
                                "teacher",
                                answer.provenance(),
                                "hypothesis",
                                answer.proposal().protectedRoadblock(),
                                "status",
                                "unverified diagnosis; protected code unchanged"));
                    } catch (RuntimeException | IOException error) {
                      last = "Rejected policy: " + error.getMessage();
                      feedback.put(
                          scope,
                          Map.of("previous_source", answer.proposal().source(), "error", last));
                      record(
                          "proposals", Map.of("scope", scope, "answer", answer, "rejected", last));
                    }
                  })) pending = false;
            });
    if (accepted) {
      attempts++;
      lastFailures = failures;
      nextAttempt = now + interval;
    } else {
      pending = false;
      nextAttempt = now + 60_000;
    }
  }

  public void rollback() {
    enqueue(
        () -> {
          reviewGeneration++;
          for (var library : libraries.values())
            try {
              library.rollback();
            } catch (IOException e) {
              warning.accept(e.getMessage());
            }
          last = "admin restored host baseline";
          record("proposals", Map.of("event", "admin_rollback"));
        });
  }

  public void reviewSoon() {
    enqueue(this::review);
  }

  public void saveKnowledge(Map<String, ?> snapshot) {
    String json =
        new Gson()
            .toJson(
                Map.of(
                    "schema",
                    1,
                    "basis",
                    "actual supported server recipe snapshot",
                    "data",
                    snapshot));
    if (json.length() > 2_000_000) {
      warning.accept("CoreAI recipe snapshot exceeded limit");
      return;
    }
    enqueue(
        () -> {
          try {
            Files.writeString(knowledgeFile, json);
          } catch (IOException e) {
            warning.accept("CoreAI knowledge: " + e.getMessage());
          }
        });
  }

  public String status() {
    return "CoreAI jobs="
        + shortId(libraries.get(Scope.JOBS).version().id())
        + "; routes="
        + shortId(libraries.get(Scope.ROUTES).version().id())
        + "; observations="
        + observations
        + "; outcomes="
        + outcomes
        + "; dropped="
        + dropped
        + "; job trial="
        + libraries.get(Scope.JOBS).trialStatus()
        + "; route trial="
        + libraries.get(Scope.ROUTES).trialStatus()
        + "; "
        + last;
  }

  private String shortId(String id) {
    return id.substring(0, Math.min(12, id.length()));
  }

  private void record(String stream, Map<String, ?> data) {
    try {
      journal.append(stream, Map.of("schema", 1, "time", System.currentTimeMillis(), "data", data));
    } catch (IOException e) {
      warning.accept("CoreAI data: " + e.getMessage());
    }
  }

  private boolean enqueue(Runnable work) {
    if (closed) return false;
    try {
      io.execute(
          () -> {
            try {
              work.run();
            } catch (RuntimeException e) {
              warning.accept("CoreAI task: " + e.getMessage());
            }
          });
      return true;
    } catch (RejectedExecutionException e) {
      dropped.incrementAndGet();
      return false;
    }
  }

  @Override
  public void close() {
    closed = true;
    timer.shutdownNow();
    io.shutdown();
  }
}
