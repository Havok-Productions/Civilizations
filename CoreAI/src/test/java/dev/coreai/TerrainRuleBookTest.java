package dev.coreai;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerrainRuleBookTest {
  @TempDir Path root;
  final TerrainRuleBook.Facts clutter =
      new TerrainRuleBook.Facts(
          "TRIPWIRE", "tripwire[attached=false]", true, true, false, true, false, false, false);

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void collisionDamageAndUnobservedSpaceCannotBeRewrittenAway() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TerrainRuleBook.validate(
                new TerrainRuleBook.Facts(
                    "STONE", "stone", true, false, true, false, false, false, false),
                "PASSABLE"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TerrainRuleBook.validate(
                new TerrainRuleBook.Facts(
                    "FIRE", "fire", true, true, false, true, false, true, false),
                "PASSABLE"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TerrainRuleBook.validate(
                new TerrainRuleBook.Facts(
                    "WATER", "water", true, true, false, false, true, false, false),
                "PASSABLE"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TerrainRuleBook.validate(
                new TerrainRuleBook.Facts(
                    "UNKNOWN", "", false, true, false, true, false, false, false),
                "CLEARABLE"));
    TerrainRuleBook.validate(clutter, "PASSABLE");
    TerrainRuleBook.validate(clutter, "CLEARABLE");
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void editedRulesAndSearchRadiusPilotThenPersistAfterObservedSuccess() throws Exception {
    var book = new TerrainRuleBook(root);
    book.stage("trial", "one", clutter, "PASSABLE", "Physical probe has no collision", "fixture");
    book.stageRadius("trial", "one", 32);
    assertNotNull(book.rule("one", clutter.material(), clutter.state()));
    assertNull(book.rule("two", clutter.material(), clutter.state()));
    assertEquals(32, book.radius("one", 20, 48));
    assertEquals(20, book.radius("two", 20, 48));
    assertEquals(24, book.radius("one", 20, 24));
    assertEquals(20, new TerrainRuleBook(root).radius("one", 20, 48));
    book.finish("trial", true);
    var restored = new TerrainRuleBook(root);
    assertNotNull(restored.rule("two", clutter.material(), clutter.state()));
    assertNull(restored.rule("two", clutter.material(), "tripwire[attached=true]"));
    assertEquals(32, restored.radius("two", 20, 48));
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void failedAndCancelledEditsRevertWithoutLosingOlderKnowledge() throws Exception {
    var book = new TerrainRuleBook(root);
    book.stage("a", "one", clutter, "PASSABLE", "test", "fixture");
    book.finish("a", true);
    book.stage("b", "one", clutter, "OBSTACLE", "test revision", "fixture");
    book.stageRadius("b", "one", 48);
    book.finish("b", false);
    assertEquals("PASSABLE", book.rule("one", clutter.material(), clutter.state()).category());
    assertEquals(20, book.radius("one", 20, 48));
    book.stageRadius("c", "one", 40);
    book.cancel("c");
    assertEquals(20, book.radius("one", 20, 48));
    assertThrows(IllegalArgumentException.class, () -> book.stageRadius("d", "one", 49));
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void failedPersistenceDiscardsAnUnpublishedTrial() throws Exception {
    var book = new TerrainRuleBook(root);
    book.stage("a", "one", clutter, "PASSABLE", "probe", "fixture");
    book.stageRadius("a", "one", 32);
    java.nio.file.Files.createDirectory(root.resolve("terrain.tmp"));
    assertThrows(java.io.IOException.class, () -> book.finish("a", true));
    assertNull(book.rule("one", clutter.material(), clutter.state()));
    assertEquals(20, book.radius("one", 20, 48));
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void classificationAndSearchAreExecutableInstructionsWithSeparateValidation() {
    String source =
        """
        {"explanation":"Classify clutter and widen the map","steps":[{"op":"CLASSIFY","x":1,"y":0,"z":0,"material":"PASSABLE"},{"op":"SEARCH","x":32,"y":0,"z":0,"material":""},{"op":"VERIFY","x":0,"y":0,"z":0,"material":""}]}
        """;
    assertEquals(3, SkillProgram.parse(source).steps().size());
    assertThrows(
        IllegalArgumentException.class,
        () -> SkillProgram.parse(source.replace("PASSABLE", "HAZARD")));
    assertThrows(
        IllegalArgumentException.class, () -> SkillProgram.parse(source.replace("32", "49")));
    assertThrows(
        IllegalArgumentException.class, () -> SkillProgram.parse(source.replace("SEARCH", "WALK")));
  }
}
