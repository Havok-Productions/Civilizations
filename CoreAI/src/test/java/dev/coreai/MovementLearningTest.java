package dev.coreai;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import dev.coreai.movement.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class MovementLearningTest {
  @TempDir Path root;

  @Test
  @Tag("coreai")
  @Tag("navigation")
  void learnsHeldOutCoursesAndKeepsPhysicalClaimsSeparate() {
    var result = MovementTraining.train(MovementCurriculum.generate(451, 200));
    var metrics = result.report().metrics();
    var test = metrics.get("virtual/test");
    assertTrue(test.count() > 200);
    assertTrue(test.accuracy() > .9, test.toString());
    assertTrue(test.brier() < metrics.get("virtual/test/constant").brier() * .5);
    assertTrue(test.brier() < metrics.get("virtual/test/untrained").brier() * .5);
    assertEquals(0, metrics.get("physical/test").count());
    var restored = new MovementNetwork(result.weights());
    var reloaded =
        new MovementNetwork(
            new Gson()
                .fromJson(new Gson().toJson(result.weights()), MovementNetwork.Weights.class));
    MovementCurriculum.generate(983, 20)
        .forEach(s -> assertEquals(restored.predict(s), reloaded.predict(s), 0));
  }

  @Test
  @Tag("coreai")
  @Tag("navigation")
  void revisionsAndRepeatedOutcomesCannotLeakAcrossPartitions() {
    var data = new ArrayList<>(MovementCurriculum.generate(451, 40));
    data.add(data.getFirst());
    var first = data.getFirst();
    data.add(
        new MovementSample(
            "cancelled",
            first.group(),
            first.family(),
            first.source(),
            first.features(),
            null,
            "night",
            12));
    var split = MovementTraining.split(data);
    assertEquals(1, split.duplicates());
    assertEquals(1, split.ignored());
    Set<String> train = new HashSet<>(), validation = new HashSet<>();
    split.train().forEach(s -> train.add(s.group()));
    split.validation().forEach(s -> validation.add(s.group()));
    assertTrue(Collections.disjoint(train, validation));
    split
        .test()
        .forEach(
            s -> {
              assertFalse(train.contains(s.group()));
              assertFalse(validation.contains(s.group()));
            });
    Map<String, Integer> partitions = new HashMap<>();
    for (var sample : data)
      assertEquals(
          partitions.computeIfAbsent(sample.group(), g -> MovementTraining.partition(sample)),
          MovementTraining.partition(sample));
  }

  @Test
  @Tag("coreai")
  @Tag("navigation")
  void commonSuccessfulWalksDoNotEraseOtherTerrainOrRareFailedAttempts() {
    var samples = new ArrayList<>(MovementCurriculum.generate(451, 80));
    for (int i = 0; i < 600; i++)
      samples.add(physical("ordinary-" + i, "native_walk", false, true));
    for (int i = 0; i < 30; i++) {
      samples.add(physical("blocked-walk-" + i, "native_walk", true, false));
      samples.add(physical("blocked-swim-" + i, "surface_swim", true, false));
      samples.add(physical("clear-swim-" + i, "surface_swim", false, true));
    }
    var result = MovementTraining.train(samples);
    var metrics = result.report().metrics();
    assertTrue(metrics.get("virtual/test").accuracy() > .9, metrics.toString());
    for (var family : List.of("native_walk", "surface_swim")) {
      var failures = metrics.get("physical/test/" + family + "/failures");
      assertTrue(failures.count() > 0);
      assertEquals(0, failures.confidentFailures(), family);
      assertTrue(failures.accuracy() > .8, failures.toString());
    }
  }

  @Test
  @Tag("coreai")
  @Tag("diagnostics")
  void promotionUsesValidationAndRejectsRegressionEvenWhenTestLabelsWouldFavorIt() {
    var samples = new ArrayList<MovementSample>();
    for (int i = 0; i < 100; i++)
      samples.add(physical("held-route-" + i, "native_walk", true, false));
    var confidentSuccess = constant(3);
    var confidentFailure = constant(-3);
    var rejected = MovementTraining.promotion(samples, confidentSuccess, confidentFailure);
    assertFalse(rejected.promoted());
    assertTrue(rejected.reason().contains("physical/native_walk"));
    var oppositeTest =
        samples.stream()
            .map(
                s ->
                    MovementTraining.partition(s) == 2
                        ? new MovementSample(
                            s.id(),
                            s.group(),
                            s.family(),
                            s.source(),
                            s.features(),
                            true,
                            "test_only_changed",
                            1)
                        : s)
            .toList();
    assertEquals(
        rejected, MovementTraining.promotion(oppositeTest, confidentSuccess, confidentFailure));
    assertTrue(MovementTraining.promotion(samples, confidentFailure, confidentSuccess).promoted());
    var noValidation = samples.stream().filter(s -> MovementTraining.partition(s) != 1).toList();
    assertEquals(
        "no_held_out_validation",
        MovementTraining.promotion(noValidation, confidentFailure, confidentSuccess).reason());
  }

  @Test
  @Tag("coreai")
  @Tag("diagnostics")
  void improvedBalancedLossCannotHideWorseValidationClassification() {
    List<MovementSample> validation = new ArrayList<>();
    for (int i = 0; validation.size() < 10; i++) {
      var sample = physical("accuracy-gate-" + i, "native_walk", true, validation.size() == 9);
      if (MovementTraining.partition(sample) == 1) validation.add(sample);
    }
    // Moving from .4 to .51 improves equally weighted class loss, but misclassifies nine failures.
    var decision =
        MovementTraining.promotion(
            validation, constant(Math.log(.51 / .49)), constant(Math.log(.4 / .6)));
    var compared = decision.validation().get("physical/native_walk");
    assertTrue(compared.candidateBalancedBrier() < compared.incumbentBalancedBrier());
    assertEquals(.1, compared.candidateAccuracy(), 1e-9);
    assertEquals(.9, compared.incumbentAccuracy(), 1e-9);
    assertEquals(0, compared.candidateConfidentFailures());
    assertFalse(decision.promoted());
    assertTrue(decision.reason().contains("physical/native_walk(accuracy)"));
  }

  private static MovementSample physical(
      String id, String family, boolean blocked, boolean success) {
    boolean swim = family.equals("surface_swim");
    return new MovementSample(
        id,
        id,
        family,
        "physical",
        List.of(
            2.0, 0.0, 0.0, 3.0, blocked ? 1.0 : 0.0, swim ? 0.0 : 1.0, swim ? 1.0 : 0.0, 0.0, 0.0),
        success,
        success ? "arrived" : "stalled",
        100);
  }

  private static MovementNetwork.Weights constant(double logit) {
    var output = new double[MovementNetwork.HIDDEN + 1];
    output[MovementNetwork.HIDDEN] = logit;
    return new MovementNetwork.Weights(
        1, new double[MovementNetwork.HIDDEN][MovementNetwork.INPUTS + 1], output);
  }

  @Test
  @Tag("coreai")
  @Tag("diagnostics")
  void correlatesOnceAndPreservesInterruptedAttemptsWithoutFailureLabels() throws Exception {
    List<String> warnings = new ArrayList<>();
    try (var learning = new MovementLearning(root, warnings::add)) {
      var sample = MovementCurriculum.generate(9, 2).getFirst();
      var observation =
          new MovementSample(
              "observed",
              "one_route",
              "native_walk",
              "physical",
              sample.features(),
              null,
              "started",
              0);
      var attempt =
          learning.begin(observation, "worker", 100, Map.of("path", List.of(List.of(1, 2, 3))));
      learning.finish(attempt, true, "arrived", 200);
      learning.finish(attempt, false, "late_timeout", 300);
      var interrupted =
          learning.begin(
              new MovementSample(
                  "interrupted",
                  "another_route",
                  "native_walk",
                  "physical",
                  sample.features(),
                  null,
                  "started",
                  0),
              "worker",
              400);
      learning.finish(interrupted, null, "night", 500);
      learning.flush().get(10, TimeUnit.SECONDS);
      var lines = Files.readAllLines(root.resolve("attempts.jsonl"));
      assertEquals(4, lines.size());
      assertTrue(lines.getFirst().contains("path"));
      var receipts =
          lines.stream().map(s -> new Gson().fromJson(s, MovementLearning.Receipt.class)).toList();
      assertEquals(2, receipts.stream().filter(r -> r.phase().equals("started")).count());
      var finished =
          receipts.stream()
              .filter(r -> r.phase().equals("finished"))
              .map(MovementLearning.Receipt::sample)
              .toList();
      assertEquals(1, finished.stream().filter(s -> Boolean.TRUE.equals(s.success())).count());
      assertEquals(1, MovementTraining.split(finished).ignored());
      assertTrue(warnings.isEmpty(), warnings.toString());
    }
  }

  @Test
  @Tag("coreai")
  @Tag("diagnostics")
  void persistsLearnedWeightsAndRecoversFromCorruptModelWithoutAffectingNavigation()
      throws Exception {
    MovementLearning.Snapshot trained;
    try (var learning = new MovementLearning(root, s -> {})) {
      var observed =
          new MovementSample(
              "remember",
              "persistent_route",
              "native_walk",
              "physical",
              MovementCurriculum.generate(9, 2).getFirst().features(),
              null,
              "started",
              0);
      learning.finish(learning.begin(observed, "worker", 100), true, "arrived", 200);
      trained = learning.train().get(30, TimeUnit.SECONDS);
    }
    assertEquals(
        1,
        new Gson()
            .fromJson(Files.readString(root.resolve("experience.json")), MovementSample[].class)
            .length);
    Files.delete(root.resolve("attempts.jsonl"));
    try (var reloaded = new MovementLearning(root, s -> fail(s))) {
      reloaded.flush().get(10, TimeUnit.SECONDS);
      assertTrue(reloaded.status().contains(trained.version()));
      var retrained = reloaded.train().get(30, TimeUnit.SECONDS);
      assertEquals(
          trained.version(),
          retrained.version(),
          "Rotating diagnostics must not erase learned examples");
    }
    Files.writeString(root.resolve("model.json"), "{broken");
    List<String> warnings = new ArrayList<>();
    try (var broken = new MovementLearning(root, warnings::add)) {
      broken.flush().get(10, TimeUnit.SECONDS);
      assertTrue(broken.status().contains("untrained"));
      assertEquals(1, warnings.size());
      assertEquals("{broken", Files.readString(root.resolve("model.json")));
    }
  }
}
