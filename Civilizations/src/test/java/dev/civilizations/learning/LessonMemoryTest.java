package dev.civilizations.learning;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import dev.civilizations.ai.*;
import dev.civilizations.world.BlockLessons;
import dev.coreai.TerrainRuleBook;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class LessonMemoryTest {
  @TempDir Path root;

  private TerrainRuleBook.Facts carrot() {
    return new TerrainRuleBook.Facts(
        "CARROTS", "minecraft:carrots[age=0]", true, true, false, true, false, false, false);
  }

  @Test
  @Tag("coreai")
  @Tag("diagnostics")
  @Tag("interaction")
  void restoresMeasuredCarrotsFromAFailedHistoricalTrialWithoutAdoptingBehavior() throws Exception {
    Files.createDirectories(root.resolve("data"));
    var gson = new Gson();
    var facts = carrot();
    var valid =
        Map.of(
            "event",
            "step",
            "data",
            Map.of(
                "evidence",
                Map.of(
                    "physical_probe",
                    facts,
                    "proposed_rule",
                    Map.of("facts", facts, "category", "PASSABLE"))));
    var wrong =
        Map.of(
            "event",
            "step",
            "data",
            Map.of(
                "evidence",
                Map.of(
                    "physical_probe",
                    facts,
                    "proposed_rule",
                    Map.of("facts", facts, "category", "OBSTACLE"))));
    Files.writeString(
        root.resolve("data/experiments.jsonl"),
        gson.toJson(wrong)
            + "\n"
            + "{\"event\":\"outcome\",\"data\":{\"success\":false,\"reason\":\"instruction_timeout\"}}\n");
    Files.writeString(root.resolve("data/experiments.1.jsonl"), gson.toJson(valid) + "\n");
    var rules = new TerrainRuleBook(root.resolve("rules"));
    assertEquals(1, LessonRecovery.restore(root, rules));
    assertEquals(
        "PASSABLE",
        new TerrainRuleBook(root.resolve("rules"))
            .rule("worker", facts.material(), facts.state())
            .category());
    assertEquals(0, LessonRecovery.restore(root, rules));
    assertEquals(0, rules.snapshot().searchRadius());
    assertTrue(rules.snapshot().parameters().isEmpty());
  }

  @Test
  @Tag("coreai")
  @Tag("navigation")
  @Tag("interaction")
  void ordinaryObservationSavesOnCloseWithoutModelCallsOrTrialSuccess() throws Exception {
    ModelBackend offline =
        new ModelBackend() {
          public boolean ready() {
            return false;
          }

          public String status() {
            return "offline fixture";
          }

          public void close() {}

          public String complete(String system, String user) {
            throw new AssertionError("No model needed for a physical fact");
          }
        };
    var warnings = new java.util.concurrent.CopyOnWriteArrayList<String>();
    try (var queue = new InferenceQueue(offline, 4)) {
      try (var learning = new RecoveryExperiments(root, queue, () -> "none", warnings::add)) {
        assertTrue(learning.remember(carrot(), BlockLessons.category(carrot()), "nearby", "host"));
        assertNotNull(
            learning.rules().rule("another-worker", "CARROTS", "minecraft:carrots[age=0]"));
      }
      assertNotNull(
          new TerrainRuleBook(root.resolve("rules"))
              .rule("new-worker", "CARROTS", "minecraft:carrots[age=0]"));
    }
    assertTrue(warnings.isEmpty(), warnings.toString());
    assertTrue(Files.readString(root.resolve("data/lessons.jsonl")).contains("facts_saved"));
    assertNull(
        BlockLessons.category(
            new TerrainRuleBook.Facts(
                "FIRE", "minecraft:fire", true, true, false, true, false, true, false)));
  }
}
