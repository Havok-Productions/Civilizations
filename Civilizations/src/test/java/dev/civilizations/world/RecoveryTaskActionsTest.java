package dev.civilizations.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import java.util.*;
import org.junit.jupiter.api.*;

final class RecoveryTaskActionsTest {
  @Test
  @Tag("coreai")
  @Tag("crafting")
  @Tag("interaction")
  void acquireCompletionRequiresActualOutputRatherThanAFeasibleRecipeOrIngredient() {
    var recipes =
        new CraftingBook(
            List.of(
                new CraftingBook.Recipe(
                    "planks", "OAK_PLANKS", 4, List.of(List.of("OAK_LOG")), false),
                new CraftingBook.Recipe(
                    "sticks", "STICK", 4, Collections.nCopies(2, List.of("OAK_PLANKS")), false),
                new CraftingBook.Recipe(
                    "wooden_pickaxe",
                    "WOODEN_PICKAXE",
                    1,
                    List.of(
                        List.of("OAK_PLANKS"),
                        List.of("OAK_PLANKS"),
                        List.of("OAK_PLANKS"),
                        List.of("STICK"),
                        List.of("STICK")),
                    true)));
    var before = Map.of("OAK_PLANKS", 3, "STICK", 2);
    var next = WorkerPlan.next(recipes, "WOODEN_PICKAXE", before, true, false).step();
    assertEquals("craft", next.action());
    assertFalse(RecoveryTaskActions.acquired("WOODEN_PICKAXE", before));
    assertFalse(RecoveryTaskActions.acquired("WOODEN_PICKAXE", Map.of("WOODEN_PICKAXE", 0)));
    assertFalse(RecoveryTaskActions.acquired("WOODEN_PICKAXE", Map.of("STONE_PICKAXE", 1)));
    assertTrue(RecoveryTaskActions.acquired("WOODEN_PICKAXE", Map.of("WOODEN_PICKAXE", 1)));
    assertEquals(Map.of("OAK_PLANKS", 3, "STICK", 2), before);
  }
}
