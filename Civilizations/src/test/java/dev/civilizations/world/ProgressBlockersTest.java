package dev.civilizations.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import dev.civilizations.design.*;
import java.util.*;
import org.junit.jupiter.api.*;

final class ProgressBlockersTest {
  private CraftingBook recipes() {
    return new CraftingBook(
        List.of(
            new CraftingBook.Recipe(
                "glass", "GLASS", 1, List.of(List.of("SAND", "RED_SAND")), false, true),
            new CraftingBook.Recipe(
                "charcoal", "CHARCOAL", 1, List.of(List.of("OAK_LOG")), false, true),
            new CraftingBook.Recipe(
                "furnace", "FURNACE", 1, Collections.nCopies(8, List.of("COBBLESTONE")), true),
            new CraftingBook.Recipe(
                "table", "CRAFTING_TABLE", 1, Collections.nCopies(4, List.of("OAK_PLANKS")), false),
            new CraftingBook.Recipe(
                "planks", "OAK_PLANKS", 4, List.of(List.of("OAK_LOG")), false)));
  }

  @Test
  @Tag("crafting")
  @Tag("tasks")
  @Tag("interaction")
  void glassPlanBuildsStationAndUsesSandWithSeparateFuel() {
    var book = recipes();
    assertEquals("COBBLESTONE", book.next("GLASS", Map.of("SAND", 1), true, false).item());
    var station = book.next("GLASS", Map.of("FURNACE", 1, "SAND", 1), true, false);
    assertEquals("place_station", station.action());
    assertEquals("FURNACE", station.item());
    assertEquals("SAND", book.next("GLASS", Map.of("OAK_LOG", 1), true, true).item());
    var step = book.next("GLASS", Map.of("RED_SAND", 1, "BIRCH_LOG", 1), true, true);
    assertEquals("smelt", step.action());
    assertEquals(Map.of("RED_SAND", 1, "BIRCH_LOG", 1), step.cost());
    assertEquals(
        Map.of("RED_SAND", 1, "BIRCH_LOG", 1),
        book.withdrawal("GLASS", Map.of(), Map.of("RED_SAND", 1, "BIRCH_LOG", 1), true));
    assertEquals("gather", book.next("GLASS", Map.of("SAND", 1), true, true).action());
  }

  @Test
  @Tag("crafting")
  void charcoalCannotConsumeTheSameLogAsInputAndFuel() {
    var missing = recipes().next("CHARCOAL", Map.of("OAK_LOG", 1), true, true);
    assertEquals("gather", missing.action());
    assertEquals(2, missing.amount());
    assertEquals(
        Map.of("OAK_LOG", 2), recipes().next("CHARCOAL", Map.of("OAK_LOG", 2), true, true).cost());
  }

  @Test
  @Tag("navigation")
  @Tag("crafting")
  @Tag("interaction")
  void resourceSurveyExpandsWithoutRepeatingTilesOrMisidentifyingGlass() {
    Pos origin = new Pos(-4163, 71, -1345);
    Set<Pos> cells = new HashSet<>();
    for (int i = 0; i < 81; i++) assertTrue(cells.add(ResourceSurvey.tileCenter(origin, i, 20)));
    assertTrue(cells.stream().anyMatch(p -> p.horizontal2(origin) > 80 * 80));
    assertTrue(MaterialSources.matches("SAND", "SAND"));
    assertFalse(MaterialSources.matches("GLASS", "SAND"));
    assertFalse(MaterialSources.matches("LOG", "STRIPPED_OAK_LOG"));
  }

  @Test
  @Tag("design")
  void rejectsCollinearWallBeforeAllocatingDistantTerrainButAllowsDistantValidLayout() {
    var invalid =
        new Blueprint(
            "wall",
            "Defense",
            -4163,
            -1342,
            12,
            8,
            3,
            "north",
            List.of(
                new Blueprint.Point(-4163, -1342), new Blueprint.Point(-4163, -1345),
                new Blueprint.Point(-4163, -1347), new Blueprint.Point(-4163, -1348)));
    assertThrows(IllegalArgumentException.class, invalid::validateGeometry);
    var distant =
        new Blueprint(
            "wall",
            "Expansion",
            80,
            -4,
            0,
            0,
            3,
            "north",
            List.of(
                new Blueprint.Point(76, -4), new Blueprint.Point(84, -4),
                new Blueprint.Point(84, 4), new Blueprint.Point(76, 4)));
    assertDoesNotThrow(distant::validateGeometry);
    var absolute =
        Blueprint.parse(
            """
            {"coordinate_space":"world","kind":"house","purpose":"Expand shelter","x":-4157,"z":-1351,"width":5,"depth":5,"height":3,"direction":"north","points":[]}
            """,
            new Pos(-4163, 71, -1345));
    assertEquals(6, absolute.x());
    assertEquals(-6, absolute.z());
    assertEquals(32, DesignSurvey.proposal(absolute, new Pos(-4163, 71, -1345)).radius());
  }
}
