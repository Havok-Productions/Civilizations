package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

class CraftingTest {

  private static CraftingBook.Recipe recipe(String output, int n, boolean table, String... slots) {
    return new CraftingBook.Recipe(
        output, output, n, Arrays.stream(slots).map(s -> List.of(s.split(","))).toList(), table);
  }

  private CraftingBook book() {
    return new CraftingBook(
        List.of(
            recipe("OAK_PLANKS", 4, false, "OAK_LOG"),
            recipe("BIRCH_PLANKS", 4, false, "BIRCH_LOG"),
            recipe(
                "CRAFTING_TABLE",
                1,
                false,
                "OAK_PLANKS,BIRCH_PLANKS",
                "OAK_PLANKS,BIRCH_PLANKS",
                "OAK_PLANKS,BIRCH_PLANKS",
                "OAK_PLANKS,BIRCH_PLANKS"),
            recipe("STICK", 4, false, "OAK_PLANKS,BIRCH_PLANKS", "OAK_PLANKS,BIRCH_PLANKS"),
            recipe(
                "WOODEN_PICKAXE",
                1,
                true,
                "OAK_PLANKS,BIRCH_PLANKS",
                "OAK_PLANKS,BIRCH_PLANKS",
                "OAK_PLANKS,BIRCH_PLANKS",
                "STICK",
                "STICK"),
            recipe(
                "STONE_PICKAXE",
                1,
                true,
                "COBBLESTONE",
                "COBBLESTONE",
                "COBBLESTONE",
                "STICK",
                "STICK"),
            recipe("TORCH", 4, false, "COAL", "STICK")));
  }

  @org.junit.jupiter.api.Tag("crafting")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void currentWorkerPlanBootstrapsWoodThenUsesServerRecipeForStoneUpgrade() {
    var job =
        new Job(Job.Kind.MINE, "mine", new Pos(0, 63, 0), new Pos(0, 64, 0), "", "IRON_ORE", null);
    var empty = dev.civilizations.world.WorkerPlan.needed(book(), job, Map.of(), false);
    assertTrue(empty.containsKey("LOG"));
    assertFalse(empty.containsKey("COBBLESTONE"));
    var equipped =
        dev.civilizations.world.WorkerPlan.needed(
            book(), job, Map.of("WOODEN_PICKAXE", 1, "STICK", 2), true);
    assertEquals(Map.of("COBBLESTONE", 3), equipped);
    var craft =
        book()
            .next("STONE_PICKAXE", Map.of("WOODEN_PICKAXE", 1, "STICK", 2, "COBBLESTONE", 3), true);
    assertEquals("craft", craft.action());
    assertTrue(craft.table());
    assertEquals(Map.of("COBBLESTONE", 3, "STICK", 2), craft.cost());
    assertEquals(1, craft.amount());
  }

  @org.junit.jupiter.api.Tag("crafting")
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"OAK", "BIRCH"})
  void woodBootstrapConservesMaterialsAndRequiresPlacedStation(String wood) {
    Map<String, Integer> inv = new HashMap<>(Map.of(wood + "_LOG", 3));
    boolean table = false;
    CraftingBook recipes = book();
    for (int i = 0; i < 16; i++) {
      var step = recipes.next("WOODEN_PICKAXE", inv, table);
      if (step.action().equals("ready")) break;
      assertNotEquals("gather", step.action(), step.toString());
      if (step.action().equals("place_station")) {
        assertFalse(table);
        table = true;
      }
      if (step.table()) assertTrue(table);
      step.cost()
          .forEach(
              (m, n) -> {
                assertTrue(inv.getOrDefault(m, 0) >= n);
                inv.merge(m, -n, Integer::sum);
              });
      if (step.action().equals("craft")) inv.merge(step.item(), step.amount(), Integer::sum);
    }
    assertTrue(table);
    assertEquals(1, inv.get("WOODEN_PICKAXE"));
    assertEquals(0, inv.get(wood + "_LOG"));
    assertEquals(3, inv.get(wood + "_PLANKS"));
    assertEquals(2, inv.get("STICK"));
    assertFalse(inv.containsKey((wood.equals("OAK") ? "BIRCH" : "OAK") + "_PLANKS"));
  }

  @org.junit.jupiter.api.Tag("crafting")
  @Test
  void mixedPlanksWorkButCannotTurnBirchIntoOak() {
    var step = book().next("CRAFTING_TABLE", Map.of("BIRCH_PLANKS", 2, "OAK_PLANKS", 2), false);
    assertEquals("craft", step.action());
    assertEquals(Map.of("BIRCH_PLANKS", 2, "OAK_PLANKS", 2), step.cost());
    assertEquals("OAK_LOG", book().next("OAK_PLANKS", Map.of("BIRCH_LOG", 10), true).item());
  }

  @org.junit.jupiter.api.Tag("crafting")
  @Test
  void coalBlockCycleTerminatesAtRawMaterial() {
    CraftingBook cyclic =
        new CraftingBook(
            List.of(
                recipe("COAL", 9, false, "COAL_BLOCK"),
                recipe(
                    "COAL_BLOCK",
                    1,
                    true,
                    "COAL",
                    "COAL",
                    "COAL",
                    "COAL",
                    "COAL",
                    "COAL",
                    "COAL",
                    "COAL",
                    "COAL")));
    var step = cyclic.next("COAL", Map.of(), true);
    assertEquals("gather", step.action());
    assertEquals("COAL", step.item());
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void urgentCoalSchemaPreservesSurveyedHeadingAndCoordinates() {
    var b =
        new dev.civilizations.design.Blueprint("mine", "Fuel", 0, -12, 0, 6, 0, "north", List.of());
    String schema =
        dev.civilizations.design.SupplyDesignSchema.coalRoutes(
            List.of(Map.of("blueprint", b, "coal_blocks_in_route", 1)));
    var props =
        com.google.gson.JsonParser.parseString(schema)
            .getAsJsonObject()
            .getAsJsonObject("properties");
    assertEquals("north", props.getAsJsonObject("direction").get("const").getAsString());
    assertEquals(-12, props.getAsJsonObject("z").get("const").getAsInt());
    assertEquals(
        dev.civilizations.design.Blueprint.SCHEMA,
        dev.civilizations.design.SupplyDesignSchema.coalRoutes(
            List.of(Map.of("blueprint", b, "coal_blocks_in_route", 0))));
  }

  @org.junit.jupiter.api.Tag("storage")
  @org.junit.jupiter.api.Tag("crafting")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void reuseStoredFinishedItemsAndIntermediateRecipeIngredients() {
    assertEquals(
        Map.of("TORCH", 1), book().withdrawal("TORCH", Map.of(), Map.of("TORCH", 12), false));
    assertEquals(
        Map.of("BIRCH_PLANKS", 4),
        book().withdrawal("CRAFTING_TABLE", Map.of(), Map.of("BIRCH_PLANKS", 4), false));
    assertEquals(
        Map.of("COAL", 1),
        book().withdrawal("TORCH", Map.of("STICK", 2), Map.of("COAL", 20), true));
    assertTrue(book().withdrawal("TORCH", Map.of("TORCH", 1), Map.of("COAL", 20), true).isEmpty());
  }

  @Tag("crafting")
  @Tag("tasks")
  @Tag("interaction")
  @Test
  void oreOutputAndExecutorBootstrapTheSameToolBeforeGathering() {
    var recipes = new ArrayList<>(book().snapshot());
    recipes.add(
        new CraftingBook.Recipe(
            "iron", "IRON_INGOT", 1, List.of(List.of("RAW_IRON")), false, true));
    recipes.add(
        recipe(
            "IRON_BLOCK",
            1,
            true,
            "IRON_INGOT",
            "IRON_INGOT",
            "IRON_INGOT",
            "IRON_INGOT",
            "IRON_INGOT",
            "IRON_INGOT",
            "IRON_INGOT",
            "IRON_INGOT",
            "IRON_INGOT"));
    var book = new CraftingBook(recipes);
    var job =
        new Job(
            Job.Kind.PLACE,
            "iron",
            new Pos(1, 65, 1),
            new Pos(0, 65, 1),
            "IRON_BLOCK",
            "AIR",
            null);
    var inv = new HashMap<String, Integer>();
    for (int tick = 0; tick < 24 && ToolRecipes.tier(inv) < 2; tick++) {
      var planned = dev.civilizations.world.WorkerPlan.next(book, job.material, inv, true, true);
      var step = planned.step();
      if (step.action().equals("gather")) {
        assertEquals(
            Set.of(planned.resource()),
            dev.civilizations.world.WorkerPlan.needed(book, job, inv, true, true).keySet());
        assertTrue(
            HarvestCatalog.required(step.item()) <= ToolRecipes.tier(inv),
            "Requested ore before making its tool");
        inv.merge(step.item(), step.amount(), Integer::sum);
      } else {
        assertEquals("craft", step.action());
        step.cost()
            .forEach(
                (m, n) -> {
                  assertTrue(inv.getOrDefault(m, 0) >= n);
                  inv.merge(m, -n, Integer::sum);
                });
        inv.merge(step.item(), step.amount(), Integer::sum);
      }
    }
    assertEquals(2, ToolRecipes.tier(inv));
    assertEquals(
        "RAW_IRON",
        dev.civilizations.world.WorkerPlan.next(book, job.material, inv, true, true).resource());
  }

  @Tag("crafting")
  @Tag("storage")
  @Tag("interaction")
  @Test
  void interruptedSmeltingRequestsFuelOnlyUntilItCanResume() {
    assertTrue(SmeltingFuel.missing(true, false, false, false, Map.of("RAW_IRON", 1)));
    assertFalse(SmeltingFuel.missing(true, false, false, false, Map.of("BIRCH_LOG", 1)));
    assertFalse(SmeltingFuel.missing(true, false, false, true, Map.of()));
    assertFalse(SmeltingFuel.missing(true, false, true, false, Map.of()));
    assertFalse(SmeltingFuel.missing(true, true, false, false, Map.of()));
    assertFalse(SmeltingFuel.missing(false, false, false, false, Map.of()));
  }
}
