package dev.civilizations.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class CraftingPerformanceTest {
  private CraftingBook branchingBook() {
    var recipes = new ArrayList<CraftingBook.Recipe>();
    recipes.add(new CraftingBook.Recipe("output", "OUTPUT", 1, List.of(List.of("R0")), false));
    for (int depth = 0; depth < 4; depth++)
      for (int variant = 0; variant < 12; variant++)
        recipes.add(
            new CraftingBook.Recipe(
                "branch-" + depth + "-" + variant,
                "R" + depth,
                1,
                Collections.nCopies(9, List.of(depth == 3 ? "RAW" : "R" + (depth + 1))),
                false));
    return new CraftingBook(recipes);
  }

  @Test
  @Tag("crafting")
  @Tag("tasks")
  @Tag("interaction")
  void branchingAlternativesStayResponsiveAndInventoryChangesInvalidateTheAnswer() {
    var book = branchingBook();
    var inventory = new HashMap<String, Integer>();
    assertTimeout(
        Duration.ofSeconds(2),
        () -> {
          var missing = book.next("OUTPUT", inventory, false);
          assertEquals("gather", missing.action());
          assertEquals("RAW", missing.item());
          for (int i = 0; i < 1000; i++)
            assertEquals(missing, book.next("OUTPUT", inventory, false));
          inventory.put("R0", 1);
          var ready = book.next("OUTPUT", inventory, false);
          assertEquals("craft", ready.action());
          assertEquals(Map.of("R0", 1), ready.cost());
          inventory.clear();
          assertEquals(missing, book.next("OUTPUT", inventory, false));
        });
  }

  @Test
  @Tag("crafting")
  @Tag("storage")
  @Tag("interaction")
  void concurrentWorkersAndStationChangesDoNotShareStaleInventoryOrFuelCosts() throws Exception {
    var book =
        new CraftingBook(
            List.of(
                new CraftingBook.Recipe(
                    "table", "PRODUCT", 1, List.of(List.of("INGREDIENT")), true),
                new CraftingBook.Recipe(
                    "glass", "GLASS", 1, List.of(List.of("SAND")), false, true)));
    try (var pool = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Callable<Void>>();
      for (int i = 0; i < 100; i++) {
        final boolean table = i % 2 == 0;
        tasks.add(
            () -> {
              var next =
                  book.next("PRODUCT", Map.of("INGREDIENT", 1, "CRAFTING_TABLE", 1), table, true);
              assertEquals(table ? "craft" : "place_station", next.action());
              var fuel = book.next("GLASS", Map.of("SAND", 1, "COAL", 2), false, true);
              assertEquals(Map.of("SAND", 1, "COAL", 1), fuel.cost());
              assertThrows(UnsupportedOperationException.class, () -> fuel.cost().put("COAL", 99));
              assertEquals(
                  "place_station",
                  book.next("GLASS", Map.of("SAND", 1, "COAL", 2, "FURNACE", 1), false, false)
                      .action());
              return null;
            });
      }
      for (var task : pool.invokeAll(tasks)) task.get();
    }
  }
}
