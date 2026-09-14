package dev.civilizations.core;

import java.util.*;

/** Pure recipe decisions shared by action validation and model observations. */
public final class RecipeCatalog {
  private RecipeCatalog() {}

  public static Map<String, Integer> cost(String output, Map<String, Integer> inventory) {
    if (inventory.getOrDefault(output, 0) > 0) return Map.of(output, 1);
    return switch (output) {
      case "OAK_PLANKS" -> Map.of("OAK_LOG", 1);
      case "OAK_FENCE_GATE" -> Map.of("OAK_LOG", 2);
      case "TORCH" ->
          inventory.getOrDefault("STICK", 0) > 0
              ? Map.of("STICK", 1, "COAL", 1)
              : inventory.getOrDefault("OAK_PLANKS", 0) >= 2
                  ? Map.of("OAK_PLANKS", 2, "COAL", 1)
                  : Map.of("OAK_LOG", 1, "COAL", 1);
      case "WHITE_BED" ->
          inventory.getOrDefault("OAK_PLANKS", 0) >= 3
              ? Map.of("OAK_PLANKS", 3, "WHITE_WOOL", 3)
              : Map.of("OAK_LOG", 1, "WHITE_WOOL", 3);
      default -> Map.of(output, 1);
    };
  }

  public static Map<String, Integer> missing(String output, Map<String, Integer> inventory) {
    Map<String, Integer> result = new LinkedHashMap<>();
    cost(output, inventory)
        .forEach(
            (m, n) -> {
              int count = n - inventory.getOrDefault(m, 0);
              if (count > 0) result.put(m, count);
            });
    return result;
  }

  public static Map<String, Integer> surplus(
      Map<String, Integer> inventory, boolean wholeTaskComplete) {
    if (!wholeTaskComplete) return Map.of();
    Map<String, Integer> result = new LinkedHashMap<>();
    inventory.forEach(
        (m, n) -> {
          int retain =
              m.endsWith("_PICKAXE") || m.equals("CRAFTING_TABLE")
                  ? 1
                  : m.equals("BREAD")
                      ? 3
                      : m.equals("WHEAT_SEEDS")
                          ? 8
                          : m.equals("WHEAT")
                              ? Math.max(0, 3 - inventory.getOrDefault("BREAD", 0)) * 3
                              : 0;
          if (n > retain) result.put(m, n - retain);
        });
    return result;
  }

  public static Map<String, Integer> leftovers(String output, Map<String, Integer> cost) {
    if (cost.size() == 1 && cost.containsKey(output)) return Map.of();
    return switch (output) {
      case "OAK_PLANKS" -> Map.of("OAK_PLANKS", 3);
      case "OAK_FENCE_GATE" -> Map.of("OAK_PLANKS", 4);
      case "TORCH" ->
          cost.containsKey("STICK")
              ? Map.of("TORCH", 3)
              : cost.containsKey("OAK_PLANKS")
                  ? Map.of("TORCH", 3, "STICK", 3)
                  : Map.of("TORCH", 3, "OAK_PLANKS", 2, "STICK", 3);
      case "WHITE_BED" -> cost.containsKey("OAK_LOG") ? Map.of("OAK_PLANKS", 1) : Map.of();
      default -> Map.of();
    };
  }
}
