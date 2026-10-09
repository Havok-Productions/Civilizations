package dev.coreai;

import static org.junit.jupiter.api.Assertions.*;

import dev.coreai.skills.PortableSkillLibrary;
import dev.coreai.skills.SkillSituation;
import dev.coreai.skills.SkillSituation.Cell;
import dev.coreai.skills.SkillSituation.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortableSkillLibraryTest {
  @TempDir Path root;
  private static final String TEACHER =
      "{\"model\":\"teacher-a\",\"artifact\":\"model.gguf\",\"sha256\":\"recorded-hash\"}";

  private static SkillProgram program() {
    return new SkillProgram(
        "clear the observed leaf barrier",
        List.of(
            new SkillProgram.Step(SkillProgram.Op.CLEAR, 1, 0, 0, ""),
            new SkillProgram.Step(SkillProgram.Op.WALK, 2, 0, 0, ""),
            new SkillProgram.Step(SkillProgram.Op.VERIFY, 0, 0, 0, "")));
  }

  private static Cell cell(Point point, String material) {
    return new Cell(
        point,
        material,
        material.toLowerCase(Locale.ROOT),
        material.equals("OAK_LEAVES") ? "SOFT" : "AIR",
        "natural",
        true);
  }

  /** Simulates the host translating observations from genuinely different world positions. */
  private static SkillSituation translated(int x, int y, int z) {
    Point origin = new Point(x, y, z);
    var absolute = List.of(new Point(x, y, z), new Point(x + 1, y, z), new Point(x + 2, y, z));
    var cells = new ArrayList<Cell>();
    for (int i = 0; i < absolute.size(); i++) {
      Point p = absolute.get(i);
      cells.add(
          cell(
              new Point(p.x() - origin.x(), p.y() - origin.y(), p.z() - origin.z()),
              i == 1 ? "OAK_LEAVES" : "AIR"));
    }
    Collections.reverse(cells); // Ordering of host map enumeration is not a precondition.
    return situation("plugin-v1:walk:rules=1", cells, Map.of("DIRT", 2));
  }

  private static List<Cell> terrain() {
    return List.of(
        cell(new Point(0, 0, 0), "AIR"),
        cell(new Point(1, 0, 0), "OAK_LEAVES"),
        cell(new Point(2, 0, 0), "AIR"));
  }

  private static SkillSituation situation(
      String scope, List<Cell> terrain, Map<String, Integer> inventory) {
    return new SkillSituation(scope, new Point(2, 0, 0), 0, terrain, inventory);
  }

  @Tag("coreai")
  @Test
  void reusesOnlyRelativeInstructionsAtTranslatedSitesAndPreservesTeacherAfterReload()
      throws Exception {
    var library = new PortableSkillLibrary(root);
    var original = translated(200, 64, -900);
    var entry = library.verifiedSuccess(original, program(), TEACHER, "host arrived at goal", 10);
    var elsewhere = translated(-4000, 80, 500);
    assertEquals(original.descriptor(), elsewhere.descriptor());
    var reused = new PortableSkillLibrary(root).reusable(elsewhere);
    assertNotNull(reused);
    assertEquals(entry.id(), reused.id());
    assertEquals(program(), reused.program());
    assertEquals(TEACHER, reused.teacher());
    assertEquals("host arrived at goal", reused.verifiedEvidence());
    assertEquals(1, reused.successes());
    assertFalse(Files.readString(root.resolve("portable-skills.json")).contains("-900"));
  }

  @Tag("coreai")
  @Test
  void changedStateProtectionInventoryGoalOrPolicyCannotBorrowSuccess() throws Exception {
    var library = new PortableSkillLibrary(root);
    library.verifiedSuccess(translated(0, 0, 0), program(), TEACHER, "arrived", 1);
    Cell leaf = terrain().get(1);
    for (Cell changed :
        List.of(
            new Cell(
                leaf.relative(),
                leaf.material(),
                "oak_leaves[waterlogged=true]",
                leaf.kind(),
                leaf.removal(),
                true),
            new Cell(
                leaf.relative(), leaf.material(), leaf.state(), leaf.kind(), "protected", true))) {
      var cells = new ArrayList<>(terrain());
      cells.set(1, changed);
      assertNull(library.reusable(situation("plugin-v1:walk:rules=1", cells, Map.of("DIRT", 2))));
    }
    assertNull(library.reusable(situation("plugin-v1:walk:rules=1", terrain(), Map.of("DIRT", 1))));
    assertNull(library.reusable(situation("plugin-v1:walk:rules=2", terrain(), Map.of("DIRT", 2))));
    assertNull(
        library.reusable(
            new SkillSituation(
                "plugin-v1:walk:rules=1", new Point(1, 0, 0), 0, terrain(), Map.of("DIRT", 2))));
    assertNull(
        library.reusable(
            new SkillSituation(
                "plugin-v1:walk:rules=1", new Point(2, 0, 0), 1, terrain(), Map.of("DIRT", 2))));
  }

  @Tag("coreai")
  @Test
  void missingAndUnobservedActionTargetsCannotBecomePortableSkills() throws Exception {
    var library = new PortableSkillLibrary(root);
    var missing = new ArrayList<>(terrain());
    missing.remove(1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            library.verifiedSuccess(
                situation("scope", missing, Map.of()), program(), TEACHER, "claimed success", 1));
    for (Cell unknown :
        List.of(
            new Cell(new Point(1, 0, 0), "OAK_LEAVES", "", "SOFT", "natural", false),
            new Cell(new Point(1, 0, 0), "UNKNOWN", "", "UNKNOWN", "unknown", true))) {
      var cells = new ArrayList<>(terrain());
      cells.set(1, unknown);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              library.verifiedSuccess(
                  situation("scope", cells, Map.of()), program(), TEACHER, "claimed success", 1));
    }
    assertFalse(Files.exists(root.resolve("portable-skills.json")));
  }

  @Tag("coreai")
  @Test
  void distantUnknownCellsAreNotMistakenForObservedTargetsOrBlankTerrain() throws Exception {
    var cells = new ArrayList<>(terrain());
    cells.add(new Cell(new Point(10, 0, 0), "UNKNOWN", "", "UNKNOWN", "unknown", false));
    var knownFootprint = situation("scope", cells, Map.of());
    var library = new PortableSkillLibrary(root);
    library.verifiedSuccess(knownFootprint, program(), TEACHER, "host arrived", 1);
    assertNotNull(library.reusable(knownFootprint));
    assertNull(library.reusable(situation("scope", terrain(), Map.of())));
    var unobservedGoal = new SkillSituation("scope", new Point(10, 0, 0), 0, cells, Map.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> library.verifiedSuccess(unobservedGoal, program(), TEACHER, "claimed success", 2));
  }

  @Tag("coreai")
  @Test
  void failedReplaySuspendsOnlyItsMatchedGeometryAndSurvivesReload() throws Exception {
    var library = new PortableSkillLibrary(root);
    var original = translated(100, 65, 200);
    var entry = library.verifiedSuccess(original, program(), TEACHER, "arrived", 1);
    var changed = situation("different policy", terrain(), Map.of("DIRT", 2));
    assertFalse(library.failedReplay(entry.id(), changed, "different experiment", 2));
    assertNotNull(library.reusable(original));
    assertTrue(library.failedReplay(entry.id(), translated(-100, 70, -200), "host blocked", 3));
    assertNull(new PortableSkillLibrary(root).reusable(original));
    var later =
        library.verifiedSuccess(original, program(), "different teacher", "arrived again", 4);
    assertEquals(TEACHER, later.teacher());
    assertEquals("arrived", later.verifiedEvidence());
    assertEquals(1, later.failures());
    assertTrue(later.suspended());
    assertNull(library.reusable(original));
    library.verifiedSuccess(changed, program(), TEACHER, "changed policy worked", 5);
    assertNotNull(library.reusable(changed));
  }

  @Tag("coreai")
  @Test
  void explanationChangesCannotEraseFailureOrOverwriteOriginalProvenance() throws Exception {
    var library = new PortableSkillLibrary(root);
    var situation = translated(0, 0, 0);
    var entry = library.verifiedSuccess(situation, program(), TEACHER, "arrived", 1);
    library.failedReplay(entry.id(), situation, "failed subsequent use", 2);
    var rephrased = new SkillProgram("another explanation", program().steps());
    var retained = library.verifiedSuccess(situation, rephrased, "other teacher", "arrived", 3);
    assertEquals(entry.id(), retained.id());
    assertEquals(entry.source(), retained.source());
    assertEquals(TEACHER, retained.teacher());
    assertNull(library.reusable(situation));
  }

  @Tag("coreai")
  @Test
  void missingPhysicalEvidenceAndCorruptedStateCannotManufactureSuccess() throws Exception {
    var library = new PortableSkillLibrary(root);
    var situation = translated(0, 0, 0);
    assertThrows(
        IllegalArgumentException.class,
        () -> library.verifiedSuccess(situation, program(), TEACHER, "", 1));
    library.verifiedSuccess(situation, program(), TEACHER, "host verified", 1);
    Path file = root.resolve("portable-skills.json");
    Files.writeString(file, Files.readString(file).replace("\"successes\":1", "\"successes\":0"));
    assertThrows(IOException.class, () -> new PortableSkillLibrary(root));
  }

  @Tag("coreai")
  @Test
  void searchProgramsRemainExecutableButAreNotFixedFootprintReusable() throws Exception {
    var search =
        SkillProgram.parse(
            "{\"explanation\":\"expand map\",\"steps\":["
                + "{\"op\":\"SEARCH\",\"x\":20,\"y\":0,\"z\":0,\"material\":\"\"}]}");
    assertFalse(translated(0, 0, 0).observedFor(search));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PortableSkillLibrary(root)
                .verifiedSuccess(
                    translated(0, 0, 0), search, TEACHER, "host arrived after remapping", 1));
  }

  @Tag("coreai")
  @Test
  void writeFailureStillSuspendsKnownBadSkillInMemory() throws Exception {
    var library = new PortableSkillLibrary(root);
    var situation = translated(0, 0, 0);
    var entry = library.verifiedSuccess(situation, program(), TEACHER, "host arrived", 1);
    Files.createDirectory(root.resolve("portable-skills.tmp"));
    assertThrows(
        IOException.class, () -> library.failedReplay(entry.id(), situation, "host blocked", 2));
    assertNull(library.reusable(situation));
    // The prior durable success is intact; the caller receives the persistence error explicitly.
    assertNotNull(new PortableSkillLibrary(root).reusable(situation));
  }
}
