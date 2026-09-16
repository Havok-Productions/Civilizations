package dev.civilizations.core;

import java.util.*;

/** Executable prerequisite graph for mining tools. All recipes conserve actual items. */
public final class ToolRecipes {
  public record Step(String action, String item, Map<String, Integer> cost, int amount) {}

  private ToolRecipes() {}

  public static int tier(Map<String, Integer> inventory) {
    if (has(inventory, "NETHERITE_PICKAXE") || has(inventory, "DIAMOND_PICKAXE")) return 3;
    if (has(inventory, "IRON_PICKAXE")) return 3;
    if (has(inventory, "STONE_PICKAXE")) return 2;
    if (has(inventory, "WOODEN_PICKAXE") || has(inventory, "GOLDEN_PICKAXE")) return 1;
    return 0;
  }

  public static int required(String block) {
    if (Set.of(
            "GOLD_ORE",
            "DEEPSLATE_GOLD_ORE",
            "DIAMOND_ORE",
            "DEEPSLATE_DIAMOND_ORE",
            "EMERALD_ORE",
            "DEEPSLATE_EMERALD_ORE",
            "REDSTONE_ORE",
            "DEEPSLATE_REDSTONE_ORE")
        .contains(block)) return 3;
    if (Set.of("LAPIS_ORE", "DEEPSLATE_LAPIS_ORE").contains(block)) return 2;
    if (Set.of("IRON_ORE", "DEEPSLATE_IRON_ORE", "COPPER_ORE", "DEEPSLATE_COPPER_ORE")
        .contains(block)) return 2;
    return Set.of(
                    "STONE",
                    "DEEPSLATE",
                    "GRANITE",
                    "DIORITE",
                    "ANDESITE",
                    "COAL_ORE",
                    "DEEPSLATE_COAL_ORE",
                    "COBBLESTONE")
                .contains(block)
            || Set.of("TUFF", "CALCITE", "NETHER_QUARTZ_ORE").contains(block)
        ? 1
        : 0;
  }

  public static String nextTool(int required, int carriedTier, boolean cobblestoneAvailable) {
    if (required >= 3 && carriedTier >= 2) return "IRON_PICKAXE";
    return required >= 2 && (carriedTier > 0 || cobblestoneAvailable)
        ? "STONE_PICKAXE"
        : "WOODEN_PICKAXE";
  }

  public static Step next(Map<String, Integer> inv, boolean table, int required) {
    if (tier(inv) >= required) return new Step("ready", "", Map.of(), 0);
    // A wooden pickaxe can mine coal. Stone is needed for copper/iron, not to bootstrap coal.
    if (required >= 2 && tier(inv) == 0 && inv.getOrDefault("COBBLESTONE", 0) < 3)
      return next(inv, table, 1);
    if (!table) {
      if (has(inv, "CRAFTING_TABLE"))
        return new Step("place_station", "CRAFTING_TABLE", Map.of("CRAFTING_TABLE", 1), 1);
      if (inv.getOrDefault("OAK_PLANKS", 0) < 4) return planks(inv);
      return new Step("craft", "CRAFTING_TABLE", Map.of("OAK_PLANKS", 4), 1);
    }
    if (inv.getOrDefault("STICK", 0) < 2) {
      if (inv.getOrDefault("OAK_PLANKS", 0) < 2) return planks(inv);
      return new Step("craft", "STICK", Map.of("OAK_PLANKS", 2), 4);
    }
    if (required >= 2) {
      if (inv.getOrDefault("COBBLESTONE", 0) < 3)
        return new Step("gather", "COBBLESTONE", Map.of(), 3);
      return new Step("craft", "STONE_PICKAXE", Map.of("COBBLESTONE", 3, "STICK", 2), 1);
    }
    if (inv.getOrDefault("OAK_PLANKS", 0) < 3) return planks(inv);
    return new Step("craft", "WOODEN_PICKAXE", Map.of("OAK_PLANKS", 3, "STICK", 2), 1);
  }

  private static Step planks(Map<String, Integer> inv) {
    return has(inv, "OAK_LOG")
        ? new Step("craft", "OAK_PLANKS", Map.of("OAK_LOG", 1), 4)
        : new Step("gather", "OAK_LOG", Map.of(), 1);
  }

  private static boolean has(Map<String, Integer> inv, String item) {
    return inv.getOrDefault(item, 0) > 0;
  }
}
