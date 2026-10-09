package dev.coreai.recovery;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * CPU-only recovery success predictor. Physical labels train; simulation never labels success.
 * Candidate weights remain shadow-only until a separate physical controller comparison exists.
 */
public final class RecoveryLearning {
  /** Offline calibration from exported host receipts. Never activates candidate weights. */
  public static void main(String[] args) throws Exception {
    if (args.length < 2)
      throw new IllegalArgumentException(
          "Usage: RecoveryLearning output-directory physical-outcomes.jsonl [...]");
    var learner = new RecoveryLearning(Path.of(args[0]));
    Gson json = new Gson();
    for (int i = 1; i < args.length; i++)
      for (String line : Files.readAllLines(Path.of(args[i])))
        if (!line.isBlank()) learner.outcome(json.fromJson(line, Sample.class));
    System.out.println(
        "Candidate saved; physical comparison and validation coverage still required before"
            + " promotion.");
  }

  public static final List<String> FEATURES =
      List.of(
          "route_length",
          "clear_count",
          "mine_count",
          "support_count",
          "has_pickaxe",
          "vertical_distance",
          "goal_standing_admissible",
          "inventory_size");

  public record Sample(
      String id,
      String group,
      String strategy,
      List<Double> features,
      boolean success,
      String reason) {
    public Sample {
      features = List.copyOf(features);
      if (id == null
          || id.isBlank()
          || group == null
          || group.isBlank()
          || features.size() != FEATURES.size()
          || features.stream().anyMatch(x -> !Double.isFinite(x)))
        throw new IllegalArgumentException("Invalid recovery sample");
    }
  }

  private final Path root;
  private final LinkedHashMap<String, Sample> samples = new LinkedHashMap<>();
  private double[] weights = new double[FEATURES.size() + 1];
  private final Gson json = new Gson();

  public RecoveryLearning(Path root) throws IOException {
    this.root = root;
    Files.createDirectories(root);
    Path dataset = root.resolve("physical-outcomes.jsonl");
    if (Files.exists(dataset)) {
      for (String line : Files.readAllLines(dataset)) {
        if (line.isBlank()) continue;
        // Fail closed on malformed evidence rather than silently promoting incomplete data.
        Sample sample = json.fromJson(line, Sample.class);
        Sample old = samples.putIfAbsent(sample.id(), sample);
        if (old != null && !old.equals(sample))
          throw new IOException("Conflicting recovery outcome " + sample.id());
      }
    }
    train();
  }

  public synchronized void outcome(Sample sample) throws IOException {
    Sample old = samples.get(sample.id());
    if (old != null) {
      if (!old.equals(sample)) throw new IOException("Conflicting recovery outcome " + sample.id());
      return;
    }
    Files.writeString(
        root.resolve("physical-outcomes.jsonl"),
        json.toJson(sample) + "\n",
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
    samples.put(sample.id(), sample);
    train();
  }

  public static String partition(String group) {
    int bucket = Math.floorMod(group.hashCode(), 5);
    return bucket == 0 ? "test" : bucket == 1 ? "validation" : "train";
  }

  public synchronized double score(List<Double> features) {
    double z = weights[FEATURES.size()];
    for (int i = 0; i < FEATURES.size(); i++) z += weights[i] * Math.clamp(features.get(i), -4, 4);
    return 1 / (1 + Math.exp(-Math.clamp(z, -35, 35)));
  }

  private void train() throws IOException {
    weights = new double[FEATURES.size() + 1];
    var training =
        samples.values().stream().filter(s -> partition(s.group()).equals("train")).toList();
    long positive = training.stream().filter(Sample::success).count(),
        negative = training.size() - positive;
    boolean trained = positive > 0 && negative > 0;
    if (trained)
      for (int epoch = 0; epoch < 120; epoch++)
        for (Sample sample : training) {
          double error = score(sample.features()) - (sample.success() ? 1 : 0);
          double rate = .06 / (sample.success() ? positive : negative);
          for (int i = 0; i < FEATURES.size(); i++)
            weights[i] -=
                rate * (error * Math.clamp(sample.features().get(i), -4, 4) + .001 * weights[i]);
          weights[FEATURES.size()] -= rate * error;
        }
    var report = new LinkedHashMap<String, Object>();
    report.put("schema", 1);
    report.put("features", FEATURES);
    report.put("training_performed", trained);
    report.put("physical_samples", samples.size());
    report.put("weights", weights);
    report.put(
        "partitions",
        Map.of(
            "train",
            metrics("train"),
            "validation",
            metrics("validation"),
            "test",
            metrics("test")));
    report.put("promoted", false);
    report.put(
        "reason",
        trained
            ? "shadow_only_requires_independent_physical_controller_comparison"
            : "insufficient_training_class_coverage");
    report.put(
        "label_scope",
        "Host-verified recovery completion or failed program segment; not whole-project completion."
            + " Interruptions and virtual predictions excluded.");
    Path temp = root.resolve("candidate.tmp");
    Files.writeString(temp, json.toJson(report));
    Files.move(temp, root.resolve("candidate.json"), StandardCopyOption.REPLACE_EXISTING);
  }

  private Map<String, Object> metrics(String partition) {
    var subset =
        samples.values().stream().filter(s -> partition(s.group()).equals(partition)).toList();
    double brier = 0;
    int correct = 0;
    for (Sample s : subset) {
      double p = score(s.features()), e = p - (s.success() ? 1 : 0);
      brier += e * e;
      if ((p >= .5) == s.success()) correct++;
    }
    return Map.of(
        "count",
        subset.size(),
        "groups",
        subset.stream().map(Sample::group).distinct().count(),
        "successes",
        subset.stream().filter(Sample::success).count(),
        "correct",
        correct,
        "brier_sum",
        brier);
  }
}
