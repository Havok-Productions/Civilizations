package dev.coreai;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.coreai.movement.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class MovementAutomaticTest {
  @TempDir Path root;

  private void outcome(MovementLearning learning, String id, Boolean success) {
    var sample =
        new MovementSample(
            id,
            "route",
            "native_walk",
            "physical",
            List.of(2.0, 0.0, 0.0, 3.0, 0.0, 1.0, 0.0, 0.0, 0.0),
            null,
            "started",
            0);
    learning.finish(
        learning.begin(sample, "worker", 10),
        success,
        success == null ? "night" : success ? "arrived" : "stalled",
        20);
  }

  @Test
  @Tag("coreai")
  @Tag("diagnostics")
  void startupReviewsWithoutCommandsAndRetrainsOnlyNewLabeledEvidence() throws Exception {
    AtomicLong clock = new AtomicLong(1_000_000);
    List<String> warnings = new ArrayList<>();
    try (var learning = new MovementLearning(root, warnings::add, clock::get)) {
      assertTrue(learning.startAutomatic().get(30, TimeUnit.SECONDS));
      assertTrue(learning.status().contains("automatic=true"));
      String original = Files.readString(root.resolve("report.json"));
      assertFalse(learning.reviewAutomatically().get(10, TimeUnit.SECONDS));
      outcome(learning, "first", false);
      assertFalse(learning.reviewAutomatically().get(10, TimeUnit.SECONDS), "Training cooldown");
      var diagnostic =
          JsonParser.parseString(Files.readString(root.resolve("diagnostics.json")))
              .getAsJsonObject();
      assertEquals(1, diagnostic.get("measured_failures").getAsInt());
      assertEquals("collecting_new_outcomes", diagnostic.get("state").getAsString());
      assertEquals(original, Files.readString(root.resolve("report.json")));
      clock.addAndGet(600_000);
      assertTrue(learning.reviewAutomatically().get(30, TimeUnit.SECONDS));
      String trained = Files.readString(root.resolve("report.json"));
      outcome(learning, "duplicate", false);
      outcome(learning, "cancelled", null);
      clock.addAndGet(600_000);
      assertFalse(learning.reviewAutomatically().get(10, TimeUnit.SECONDS));
      assertEquals(trained, Files.readString(root.resolve("report.json")));
      var end =
          JsonParser.parseString(Files.readString(root.resolve("diagnostics.json")))
              .getAsJsonObject();
      assertEquals(1, end.get("interrupted").getAsInt());
      assertEquals(2, end.get("measured_failures").getAsInt());
      assertTrue(warnings.isEmpty(), warnings.toString());
    }
    try (var restarted = new MovementLearning(root, warnings::add, clock::get)) {
      assertFalse(
          restarted.startAutomatic().get(30, TimeUnit.SECONDS),
          "Restart must retain evaluated data signature");
    }
  }

  @Test
  @Tag("coreai")
  @Tag("diagnostics")
  void brokenExperienceIsReportedAndCanRecoverWithoutManualCommand() throws Exception {
    Files.writeString(root.resolve("experience.json"), "broken");
    List<String> warnings = new ArrayList<>();
    try (var learning = new MovementLearning(root, warnings::add)) {
      assertThrows(
          java.util.concurrent.ExecutionException.class,
          () -> learning.startAutomatic().get(10, TimeUnit.SECONDS));
      assertFalse(Files.exists(root.resolve("model.json")));
      assertEquals("broken", Files.readString(root.resolve("experience.json")));
      assertTrue(Files.readString(root.resolve("diagnostics.json")).contains("review_failed"));
      Files.writeString(root.resolve("experience.json"), "[]");
      assertTrue(learning.reviewAutomatically().get(30, TimeUnit.SECONDS));
      assertFalse(warnings.isEmpty());
    }
  }

  @Test
  @Tag("coreai")
  @Tag("diagnostics")
  void rejectedCandidateKeepsActiveFileAndRecordsEvaluationAcrossRestart() throws Exception {
    var trained = MovementTraining.train(MovementCurriculum.generate(451, 200));
    var json = new GsonBuilder().serializeNulls().create();
    String version = MovementTraining.digest(json.toJson(trained.weights())).substring(0, 16);
    var incumbent =
        new MovementLearning.Snapshot(
            1, version, MovementSample.FEATURES, trained.weights(), trained.report());
    String original = new GsonBuilder().setPrettyPrinting().create().toJson(incumbent);
    Files.writeString(root.resolve("model.json"), original);
    AtomicLong clock = new AtomicLong(2_000_000);
    try (var learning = new MovementLearning(root, s -> fail(s), clock::get)) {
      assertTrue(learning.startAutomatic().get(30, TimeUnit.SECONDS));
      var report =
          JsonParser.parseString(Files.readString(root.resolve("report.json"))).getAsJsonObject();
      assertFalse(report.getAsJsonObject("promotion").get("promoted").getAsBoolean());
      assertEquals(
          "no_validation_improvement",
          report.getAsJsonObject("promotion").get("reason").getAsString());
      assertEquals(original, Files.readString(root.resolve("model.json")));
      assertEquals(version, report.getAsJsonObject("candidate").get("version").getAsString());
      assertTrue(
          Files.exists(
              root.resolve("datasets")
                  .resolve(report.get("samples_sha256").getAsString() + ".json")));
      clock.addAndGet(600_000);
      assertFalse(learning.reviewAutomatically().get(10, TimeUnit.SECONDS));
    }
    try (var restarted = new MovementLearning(root, s -> fail(s), clock::get)) {
      assertFalse(restarted.startAutomatic().get(30, TimeUnit.SECONDS));
      assertTrue(restarted.status().contains(version));
      assertEquals(original, Files.readString(root.resolve("model.json")));
    }
  }
}
