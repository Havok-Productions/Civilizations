package dev.coreai.movement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.ToDoubleFunction;

/** Stable terrain-group holdout. Test labels never select weights or training duration. */
public final class MovementTraining {
  public record Metrics(
      int count, int successes, double accuracy, double brier, int confidentFailures) {}

  public record Mistake(
      String id,
      String group,
      String family,
      String source,
      List<Double> features,
      double probability,
      boolean actual,
      String reason) {}

  public record Split(
      List<MovementSample> train,
      List<MovementSample> validation,
      List<MovementSample> test,
      int ignored,
      int duplicates) {}

  public record Report(
      int schema,
      String mode,
      int train,
      int validation,
      int test,
      int ignored,
      int duplicates,
      Map<String, Metrics> metrics,
      List<Mistake> mistakes,
      String limitation) {}

  public record Result(MovementNetwork.Weights weights, Report report) {}

  public record Comparison(
      int count,
      int successes,
      double candidateBalancedBrier,
      double incumbentBalancedBrier,
      double candidateAccuracy,
      double incumbentAccuracy,
      int candidateConfidentFailures,
      int incumbentConfidentFailures) {}

  /** Only validation observations select promotion. Test metrics are never consulted. */
  public record Promotion(boolean promoted, String reason, Map<String, Comparison> validation) {}

  private MovementTraining() {}

  public static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static int partition(MovementSample sample) {
    // No timestamp, outcome or changing terrain state in group; all revisions stay together.
    int bucket =
        Integer.parseInt(digest(sample.source() + ":" + sample.group()).substring(0, 4), 16) % 100;
    return bucket < 70 ? 0 : bucket < 85 ? 1 : 2;
  }

  public static Split split(List<MovementSample> samples) {
    List<List<MovementSample>> sets =
        List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    Set<String> seen = new HashSet<>();
    int ignored = 0, duplicates = 0;
    for (var s : samples) {
      if (s.success() == null) {
        ignored++;
        continue;
      }
      // A repeated attempt at the same geometry/outcome does not swamp new experience.
      String key = s.source() + ":" + s.group() + ":" + s.features() + ":" + s.success();
      if (!seen.add(key)) {
        duplicates++;
        continue;
      }
      sets.get(partition(s)).add(s);
    }
    return new Split(
        List.copyOf(sets.get(0)),
        List.copyOf(sets.get(1)),
        List.copyOf(sets.get(2)),
        ignored,
        duplicates);
  }

  public static Result train(List<MovementSample> samples) {
    Split split = split(samples);
    if (split.train().isEmpty())
      throw new IllegalArgumentException("No labeled training terrain groups");
    MovementNetwork baseline = new MovementNetwork(451), network = new MovementNetwork(451);
    network.trainBalanced(split.train(), 100, 451);
    Map<String, Metrics> metrics = new TreeMap<>();
    for (String source : List.of("virtual", "physical")) {
      var training = split.train().stream().filter(s -> s.source().equals(source)).toList();
      double prior =
          training.isEmpty()
              ? .5
              : training.stream().filter(s -> s.success()).count() / (double) training.size();
      for (String name : List.of("validation", "test")) {
        var set =
            (name.equals("test") ? split.test() : split.validation())
                .stream().filter(s -> s.source().equals(source)).toList();
        String key = source + "/" + name;
        metrics.put(key, measure(set, network::predict));
        metrics.put(key + "/untrained", measure(set, baseline::predict));
        metrics.put(key + "/constant", measure(set, s -> prior));
        set.stream()
            .map(MovementSample::family)
            .distinct()
            .forEach(
                family -> {
                  var familySet = set.stream().filter(s -> s.family().equals(family)).toList();
                  metrics.put(key + "/" + family, measure(familySet, network::predict));
                  for (boolean outcome : List.of(false, true))
                    metrics.put(
                        key + "/" + family + (outcome ? "/successes" : "/failures"),
                        measure(
                            familySet.stream().filter(s -> s.success() == outcome).toList(),
                            network::predict));
                });
      }
    }
    var mistakeCounts = new HashMap<String, Integer>();
    var mistakes =
        split.test().stream()
            .filter(s -> (network.predict(s) >= .5) != s.success())
            .sorted(
                Comparator.comparingDouble(
                        (MovementSample s) -> Math.abs(network.predict(s) - (s.success() ? 1 : 0)))
                    .reversed())
            // A flood of virtual errors must not hide the few measured physical failures.
            .filter(s -> mistakeCounts.merge(s.source() + "/" + s.family(), 1, Integer::sum) <= 5)
            .map(
                s ->
                    new Mistake(
                        s.id(),
                        s.group(),
                        s.family(),
                        s.source(),
                        s.features(),
                        network.predict(s),
                        s.success(),
                        s.reason()))
            .toList();
    return new Result(
        network.weights(),
        new Report(
            2,
            "shadow",
            split.train().size(),
            split.validation().size(),
            split.test().size(),
            split.ignored(),
            split.duplicates(),
            Map.copyOf(metrics),
            mistakes,
            "Virtual labels come from an approximate corridor simulator, not Minecraft physics."
                + " Physical metrics require executed attempts on held-out terrain groups. Repeated"
                + " reports reuse a fixed holdout; they are not independent trials. Training"
                + " replays source/family/outcome strata equally; each metric retains its actual"
                + " sample counts. Balanced outputs are uncalibrated scores, not measured arrival"
                + " odds. Test metrics report performance but never select promotion. This model"
                + " estimates arrivals; it does not generate paths or override collision"
                + " checks."));
  }

  public static Promotion promotion(
      List<MovementSample> samples,
      MovementNetwork.Weights candidateWeights,
      MovementNetwork.Weights incumbentWeights) {
    var candidate = new MovementNetwork(candidateWeights);
    var incumbent =
        incumbentWeights == null ? new MovementNetwork(451) : new MovementNetwork(incumbentWeights);
    var validation = split(samples).validation();
    Map<String, List<MovementSample>> domains = new TreeMap<>();
    for (var sample : validation)
      domains
          .computeIfAbsent(
              sample.source().equals("virtual") ? "virtual" : "physical/" + sample.family(),
              key -> new ArrayList<>())
          .add(sample);
    Map<String, Comparison> comparisons = new TreeMap<>();
    List<String> regressions = new ArrayList<>();
    boolean improved = false;
    for (var entry : domains.entrySet()) {
      var set = entry.getValue();
      var after = measure(set, candidate::predict);
      var before = measure(set, incumbent::predict);
      double candidateLoss = balancedBrier(set, candidate::predict);
      double incumbentLoss = balancedBrier(set, incumbent::predict);
      comparisons.put(
          entry.getKey(),
          new Comparison(
              set.size(),
              after.successes(),
              candidateLoss,
              incumbentLoss,
              after.accuracy(),
              before.accuracy(),
              after.confidentFailures(),
              before.confidentFailures()));
      List<String> worsened = new ArrayList<>();
      if (candidateLoss > incumbentLoss + 1e-9) worsened.add("balanced_brier");
      if (after.accuracy() < before.accuracy() - 1e-9) worsened.add("accuracy");
      if (after.confidentFailures() > before.confidentFailures())
        worsened.add("confident_failures");
      if (!worsened.isEmpty())
        regressions.add(entry.getKey() + "(" + String.join(",", worsened) + ")");
      if (candidateLoss < incumbentLoss - 1e-9) improved = true;
    }
    String reason =
        validation.isEmpty()
            ? "no_held_out_validation"
            : !regressions.isEmpty()
                ? "validation_regression:" + String.join(",", regressions)
                : !improved
                    ? "no_validation_improvement"
                    : incumbentWeights == null ? "validated_initial_model" : "validation_improved";
    return new Promotion(
        !validation.isEmpty() && regressions.isEmpty() && improved,
        reason,
        Map.copyOf(comparisons));
  }

  private static double balancedBrier(
      List<MovementSample> set, ToDoubleFunction<MovementSample> predictor) {
    double loss = 0;
    int classes = 0;
    for (boolean outcome : List.of(false, true)) {
      var group = set.stream().filter(s -> s.success() == outcome).toList();
      if (group.isEmpty()) continue;
      loss += measure(group, predictor).brier();
      classes++;
    }
    return classes == 0 ? 0 : loss / classes;
  }

  public static Metrics measure(
      List<MovementSample> samples, ToDoubleFunction<MovementSample> predictor) {
    int correct = 0, successes = 0, confident = 0;
    double loss = 0;
    for (var s : samples) {
      double p = predictor.applyAsDouble(s);
      boolean yes = Boolean.TRUE.equals(s.success());
      if (yes) successes++;
      if ((p >= .5) == yes) correct++;
      if (p >= .8 && !yes) confident++;
      loss += Math.pow(p - (yes ? 1 : 0), 2);
    }
    return new Metrics(
        samples.size(),
        successes,
        samples.isEmpty() ? 0 : correct / (double) samples.size(),
        samples.isEmpty() ? 0 : loss / samples.size(),
        confident);
  }
}
