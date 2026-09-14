package dev.coreai;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

final class ParameterLearningTest {
  @TempDir Path root;

  @Test
  @Tag("coreai")
  void tuningIsPilotScopedPersistsAndCanBeRevisedWithoutLosingPriorRules() throws Exception {
    var book = new TerrainRuleBook(root);
    String key = "construction.interval_ms";
    book.stageParameter("first", "worker", key, 750);
    assertEquals(750, book.parameter("worker", key, 1000));
    assertEquals(1000, book.parameter("other", key, 1000));
    book.finish("first", true);
    var restored = new TerrainRuleBook(root);
    assertEquals(750, restored.parameter("other", key, 1000));
    restored.stageParameter("failed", "worker", key, 500);
    restored.finish("failed", false);
    assertEquals(750, restored.parameter("worker", key, 1000));
    restored.stageParameter("cancelled", "worker", key, 500);
    restored.cancel("cancelled");
    assertEquals(750, restored.parameter("worker", key, 1000));
    restored.stageParameter("revision", "worker", key, 800);
    restored.finish("revision", true);
    assertEquals(800, new TerrainRuleBook(root).parameter("other", key, 1000));
  }

  @Test
  @Tag("coreai")
  void oldStateMigratesAndFailedWritesDoNotPublishTuning() throws Exception {
    Files.writeString(root.resolve("terrain.json"), "{\"rules\":[],\"searchRadius\":32}");
    var book = new TerrainRuleBook(root);
    assertEquals(32, book.radius("worker", 20, 48));
    assertEquals(12, book.parameter("worker", "construction.reach_squared", 12));
    book.stageParameter("trial", "worker", "construction.reach_squared", 9);
    Files.createDirectory(root.resolve("terrain.tmp"));
    assertThrows(java.io.IOException.class, () -> book.finish("trial", true));
    assertEquals(12, book.parameter("worker", "construction.reach_squared", 12));
  }

  @Test
  @Tag("coreai")
  void executableTuningAcceptsValuesBeyondFormerRangesAndChecksInstructionShape() {
    String source =
        """
        {"explanation":"Allow a slow transition time to finish","steps":[
        {"op":"TUNE","x":15000,"y":0,"z":0,"material":"navigation.transition_ms"},
        {"op":"VERIFY","x":0,"y":0,"z":0,"material":""}]}
        """;
    assertEquals(SkillProgram.Op.TUNE, SkillProgram.parse(source).steps().getFirst().op());
    assertDoesNotThrow(() -> SkillProgram.parse(source.replace("15000", "1")));
    assertDoesNotThrow(() -> SkillProgram.parse(source.replace("15000", "500000")));
    assertFalse(SkillProgram.schema().contains("minimum"));
    assertFalse(SkillProgram.schema().contains("maximum"));
    assertThrows(
        IllegalArgumentException.class,
        () -> SkillProgram.parse(source.replace("navigation.transition_ms", "protection.enabled")));
    assertThrows(
        IllegalArgumentException.class,
        () -> SkillProgram.parse(source.replace("\"y\":0", "\"y\":1")));
    for (var e : ParameterCatalog.SPECS.entrySet()) {
      assertDoesNotThrow(() -> ParameterCatalog.validate(e.getKey(), e.getValue().defaultValue()));
      assertTrue(SkillProgram.schema().contains(e.getKey()));
    }
  }
}
