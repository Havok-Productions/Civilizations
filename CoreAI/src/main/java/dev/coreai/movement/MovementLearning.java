package dev.coreai.movement;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Local asynchronous training and pre-action/terminal receipts, independent of Bukkit and LLMs. */
public final class MovementLearning implements AutoCloseable {
  public record Snapshot(
      int schema,
      String version,
      List<String> features,
      MovementNetwork.Weights weights,
      MovementTraining.Report report) {}

  public record Receipt(
      int schema,
      long time,
      String phase,
      String worker,
      String model,
      Double prediction,
      MovementSample sample,
      Map<String, ?> context) {}

  public static final class Attempt {
    private final MovementSample sample;
    private final String worker, model;
    private final Double prediction;
    private final long started;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final Map<String, ?> context;

    private Attempt(
        MovementSample sample,
        String worker,
        String model,
        Double prediction,
        long now,
        Map<String, ?> context) {
      this.sample = sample;
      this.worker = worker;
      this.model = model;
      this.prediction = prediction;
      started = now;
      this.context = Map.copyOf(context);
    }

    public Map<String, Object> estimate() {
      return Map.of(
          "mode",
          "shadow",
          "model",
          model,
          "attempt",
          sample.id(),
          "arrival_probability",
          prediction == null ? "not_trained" : prediction,
          "calibration",
          "uncalibrated_shadow_score");
    }
  }

  private record Active(Snapshot snapshot, MovementNetwork network) {}

  private static final Gson JSON = new GsonBuilder().serializeNulls().create();
  private final Path root;
  private final Consumer<String> warning;
  private final ThreadPoolExecutor io;
  private final AtomicLong dropped = new AtomicLong(), written = new AtomicLong();
  private final AtomicBoolean training = new AtomicBoolean();
  private final AtomicBoolean reviewing = new AtomicBoolean();
  private final java.util.function.LongSupplier clock;
  private volatile ScheduledExecutorService automatic;
  private CompletableFuture<Boolean> firstReview;
  private String trainedSignature = "";
  private long lastTrainedAt;
  private volatile Active active;
  private volatile MovementTraining.Promotion lastPromotion;
  private volatile String last = "loading";

  public MovementLearning(Path root, Consumer<String> warning) {
    this(root, warning, System::currentTimeMillis);
  }

  public MovementLearning(
      Path root, Consumer<String> warning, java.util.function.LongSupplier clock) {
    this.root = root;
    this.warning = warning;
    this.clock = clock;
    io =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(512),
            r -> {
              Thread t = new Thread(r, "CoreAI-movement");
              t.setDaemon(true);
              return t;
            });
    io.execute(
        () -> {
          try {
            Files.createDirectories(root);
            String saved = null;
            if (Files.exists(root.resolve("model.json")))
              saved = Files.readString(root.resolve("model.json"));
            else
              try (var resource = MovementLearning.class.getResourceAsStream("bootstrap.json")) {
                if (resource != null)
                  saved =
                      new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
              }
            if (saved != null) {
              Snapshot snapshot = JSON.fromJson(saved, Snapshot.class);
              if (snapshot.schema() != 1 || !MovementSample.FEATURES.equals(snapshot.features()))
                throw new IOException("Feature schema mismatch");
              active = new Active(snapshot, new MovementNetwork(snapshot.weights()));
              last = "loaded shadow model";
            } else last = "collecting; automatic startup evaluation pending";
          } catch (Exception e) {
            last = "model load failed; collection continues";
            warning.accept("Movement learning: " + e.getMessage());
          }
          try {
            if (Files.exists(root.resolve("report.json"))) {
              var report =
                  com.google.gson.JsonParser.parseString(
                          Files.readString(root.resolve("report.json")))
                      .getAsJsonObject();
              if (report.has("learning_signature"))
                trainedSignature = report.get("learning_signature").getAsString();
              if (report.has("generated_at"))
                lastTrainedAt = report.get("generated_at").getAsLong();
              if (report.has("promotion"))
                lastPromotion =
                    JSON.fromJson(report.get("promotion"), MovementTraining.Promotion.class);
            }
          } catch (Exception e) {
            warning.accept("Movement evaluation metadata: " + e.getMessage());
          }
        });
  }

  /** Enable the plugin lifecycle; command-line probes can still run just once. */
  public synchronized CompletableFuture<Boolean> startAutomatic() {
    if (automatic != null) return firstReview;
    if (io.isShutdown())
      return CompletableFuture.failedFuture(new IllegalStateException("Movement learning closed"));
    automatic =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread thread = new Thread(r, "CoreAI-movement-review");
              thread.setDaemon(true);
              return thread;
            });
    firstReview = reviewAutomatically();
    automatic.scheduleWithFixedDelay(this::reviewAutomatically, 60, 60, TimeUnit.SECONDS);
    return firstReview;
  }

  /** Runs diagnostics without world access. Returns whether this review trained a model. */
  public CompletableFuture<Boolean> reviewAutomatically() {
    if (!reviewing.compareAndSet(false, true)) return CompletableFuture.completedFuture(false);
    CompletableFuture<Boolean> result = new CompletableFuture<>();
    var completed =
        result.whenComplete(
            (ignored, error) -> {
              reviewing.set(false);
              if (error != null) warning.accept("Automatic movement review: " + error.getMessage());
            });
    if (!submit(
        () -> {
          try {
            var data = MovementDataset.read(root);
            boolean initial =
                trainedSignature.isEmpty() || !Files.exists(root.resolve("report.json"));
            boolean changed = !data.signature().equals(trainedSignature);
            boolean ready = initial || changed && clock.getAsLong() - lastTrainedAt >= 600_000;
            String state =
                ready
                    ? "evaluation_due"
                    : changed ? "collecting_new_outcomes" : "no_new_labeled_geometry";
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("schema", 1);
            diagnostics.put("time", clock.getAsLong());
            diagnostics.put("mode", "automatic_shadow");
            diagnostics.put("state", state);
            diagnostics.put(
                "next_training_eligible_at", initial ? clock.getAsLong() : lastTrainedAt + 600_000);
            diagnostics.put("measured_arrivals", data.successes());
            diagnostics.put("measured_failures", data.failures());
            diagnostics.put("interrupted", data.interrupted());
            diagnostics.put("unfinished_in_retained_window", data.unfinished());
            diagnostics.put("outcome_reasons", data.reasons());
            diagnostics.put("malformed", data.malformed());
            diagnostics.put("dropped", dropped.get());
            diagnostics.put("promotion", lastPromotion);
            diagnostics.put(
                "coverage",
                data.finished() == 0
                    ? "No terminal movement receipts yet; unexecuted plans are not movement"
                        + " examples. Check navigation failure diagnostics."
                    : "Counts cover retained physical receipts only; virtual scores do not"
                        + " establish physical reliability.");
            save(root.resolve("diagnostics.json"), JSON.toJson(diagnostics));
            if (ready && !training.get()) {
              train()
                  .whenComplete(
                      (snapshot, error) -> {
                        if (error != null) {
                          automaticFailure(error);
                          result.completeExceptionally(error);
                          return;
                        }
                        try {
                          diagnostics.put("state", "evaluation_completed");
                          diagnostics.put(
                              "model", active == null ? "none" : active.snapshot().version());
                          diagnostics.put("promotion", lastPromotion);
                          save(root.resolve("diagnostics.json"), JSON.toJson(diagnostics));
                          result.complete(true);
                        } catch (IOException failed) {
                          result.completeExceptionally(failed);
                        }
                      });
            } else result.complete(false);
          } catch (Exception error) {
            automaticFailure(error);
            result.completeExceptionally(error);
          }
        })) result.completeExceptionally(new IllegalStateException("Movement queue unavailable"));
    return completed;
  }

  private void automaticFailure(Throwable error) {
    last = "automatic review failed; will retry: " + error.getMessage();
    try {
      save(
          root.resolve("diagnostics.json"),
          JSON.toJson(
              Map.of(
                  "schema",
                  1,
                  "time",
                  clock.getAsLong(),
                  "mode",
                  "automatic_shadow",
                  "state",
                  "review_failed",
                  "error",
                  error.toString())));
    } catch (IOException failed) {
      error.addSuppressed(failed);
    }
  }

  public Attempt begin(MovementSample observation, String worker, long now) {
    return begin(observation, worker, now, Map.of());
  }

  /** Context must contain immutable values only, never host entities or world handles. */
  public Attempt begin(
      MovementSample observation, String worker, long now, Map<String, ?> context) {
    if (!observation.source().equals("physical") || observation.success() != null)
      throw new IllegalArgumentException("Begin requires unlabeled physical observations");
    Active model = active;
    var attempt =
        new Attempt(
            observation,
            worker,
            model == null ? "none" : model.snapshot().version(),
            model == null ? null : model.network().predict(observation),
            now,
            context);
    record(
        new Receipt(
            1,
            now,
            "started",
            worker,
            attempt.model,
            attempt.prediction,
            observation,
            attempt.context));
    return attempt;
  }

  public void finish(Attempt attempt, Boolean success, String reason, long now) {
    if (attempt == null || !attempt.finished.compareAndSet(false, true)) return;
    var before = attempt.sample;
    var sample =
        new MovementSample(
            before.id(),
            before.group(),
            before.family(),
            "physical",
            before.features(),
            success,
            reason,
            Math.max(0, now - attempt.started));
    record(
        new Receipt(
            1,
            now,
            "finished",
            attempt.worker,
            attempt.model,
            attempt.prediction,
            sample,
            attempt.context));
  }

  private void record(Receipt receipt) {
    submit(
        () -> {
          try {
            Files.createDirectories(root);
            Path file = root.resolve("attempts.jsonl");
            if (Files.exists(file) && Files.size(file) > 8 * 1024 * 1024) {
              for (int i = 3; i >= 1; i--) {
                Path old =
                    root.resolve(i == 1 ? "attempts.jsonl" : "attempts." + (i - 1) + ".jsonl");
                if (Files.exists(old))
                  Files.move(
                      old,
                      root.resolve("attempts." + i + ".jsonl"),
                      StandardCopyOption.REPLACE_EXISTING);
              }
            }
            Files.writeString(
                file,
                JSON.toJson(receipt) + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
            written.incrementAndGet();
          } catch (IOException e) {
            dropped.incrementAndGet();
            warning.accept("Movement receipt: " + e.getMessage());
          }
        });
  }

  private boolean submit(Runnable work) {
    try {
      io.execute(work);
      return true;
    } catch (RejectedExecutionException e) {
      dropped.incrementAndGet();
      return false;
    }
  }

  public String status() {
    Active model = active;
    return "movement="
        + (model == null ? "untrained" : model.snapshot().version())
        + " (shadow); receipts this session="
        + written
        + "; dropped="
        + dropped
        + "; automatic="
        + (automatic != null)
        + "; "
        + last;
  }

  public CompletableFuture<Snapshot> train() {
    if (!training.compareAndSet(false, true))
      return CompletableFuture.failedFuture(
          new IllegalStateException("Movement training already running"));
    CompletableFuture<Snapshot> result = new CompletableFuture<>();
    if (!submit(
        () -> {
          last = "training in background";
          try {
            var data = MovementDataset.read(root);
            var samples = data.samples();
            Path experienceFile = root.resolve("experience.json");
            var trained = MovementTraining.train(samples);
            // Carry measured lessons into later training even after diagnostic logs rotate.
            Map<String, MovementSample> experience = new LinkedHashMap<>();
            for (var sample : samples)
              if (sample.source().equals("physical") && sample.success() != null)
                experience.putIfAbsent(
                    sample.group() + ":" + sample.features() + ":" + sample.success(), sample);
            save(experienceFile, JSON.toJson(experience.values()));
            String dataset = JSON.toJson(samples);
            String datasetHash = MovementTraining.digest(dataset);
            save(root.resolve("datasets").resolve(datasetHash + ".json"), dataset);
            String version =
                MovementTraining.digest(JSON.toJson(trained.weights())).substring(0, 16);
            Snapshot snapshot =
                new Snapshot(
                    1, version, MovementSample.FEATURES, trained.weights(), trained.report());
            save(root.resolve("models").resolve(version + ".json"), JSON.toJson(snapshot));
            Active incumbent = active;
            var promotion =
                MovementTraining.promotion(
                    samples,
                    trained.weights(),
                    incumbent == null ? null : incumbent.snapshot().weights());
            Snapshot selected =
                promotion.promoted() ? snapshot : incumbent == null ? null : incumbent.snapshot();
            // One atomic document couples weights and their evaluation; no partial promotion.
            if (selected != null
                && (promotion.promoted() || !Files.exists(root.resolve("model.json"))))
              save(root.resolve("model.json"), JSON.toJson(selected));
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("snapshot", selected);
            report.put("candidate", snapshot);
            report.put("promotion", promotion);
            report.put("samples_sha256", datasetHash);
            report.put("generated_at", clock.getAsLong());
            report.put("malformed_receipts", data.malformed());
            report.put("dropped_receipts", dropped.get());
            report.put("unfinished_attempts_in_retained_window", data.unfinished());
            report.put("learning_signature", data.signature());
            report.put("curriculum_seed", 451);
            report.put("curriculum_per_family", 200);
            save(root.resolve("report.json"), JSON.toJson(report));
            if (promotion.promoted())
              active = new Active(snapshot, new MovementNetwork(snapshot.weights()));
            lastPromotion = promotion;
            // A rejected candidate is an evaluated dataset too, not a failed training attempt.
            trainedSignature = data.signature();
            lastTrainedAt = clock.getAsLong();
            last =
                (promotion.promoted() ? "candidate promoted" : "incumbent retained")
                    + "; "
                    + promotion.reason()
                    + "; report.json; malformed receipts="
                    + data.malformed();
            result.complete(selected == null ? snapshot : selected);
          } catch (Exception e) {
            last = "training failed: " + e.getMessage();
            result.completeExceptionally(e);
          } finally {
            training.set(false);
          }
        })) {
      training.set(false);
      result.completeExceptionally(new IllegalStateException("Movement queue unavailable"));
    }
    return result;
  }

  private static void save(Path path, String text) throws IOException {
    Files.createDirectories(path.getParent());
    Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
    Files.writeString(temporary, text);
    try {
      Files.move(
          temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  public CompletableFuture<Void> flush() {
    CompletableFuture<Void> result = new CompletableFuture<>();
    if (!submit(() -> result.complete(null)))
      result.completeExceptionally(new IllegalStateException("Movement queue unavailable"));
    return result;
  }

  @Override
  public synchronized void close() {
    if (automatic != null) automatic.shutdownNow();
    io.shutdown();
  }

  /** Offline probe against the same curriculum and available physical receipts as the plugin. */
  public static void main(String[] args) throws Exception {
    if (args.length != 1)
      throw new IllegalArgumentException("Usage: MovementLearning <movement-data-folder>");
    try (var learning = new MovementLearning(Path.of(args[0]), System.err::println)) {
      var snapshot = learning.train().get(5, TimeUnit.MINUTES);
      System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(snapshot.report()));
    }
  }
}
