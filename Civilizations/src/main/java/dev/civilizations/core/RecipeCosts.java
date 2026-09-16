package dev.civilizations.core;

import java.util.*;

/** One immutable inventory view. Shared ingredient branches are evaluated only once per path. */
final class RecipeCosts {
  private record Distance(String item, Set<String> ancestors) {}

  private final Map<String, List<CraftingBook.Recipe>> recipes;
  private final Map<String, Integer> inventory;
  private final Map<Distance, Integer> distances = new HashMap<>();
  private final Map<CraftingBook.Recipe, Map<String, Integer>> costs = new HashMap<>();

  RecipeCosts(Map<String, List<CraftingBook.Recipe>> recipes, Map<String, Integer> inventory) {
    this.recipes = recipes;
    this.inventory = Map.copyOf(inventory);
  }

  int score(CraftingBook.Recipe recipe) {
    int result = 0;
    for (var e : cost(recipe).entrySet()) {
      int missing = Math.max(0, e.getValue() - inventory.getOrDefault(e.getKey(), 0));
      if (missing > 0) result += 100 + rawDistance(e.getKey(), Set.of()) * 10 + missing;
    }
    return result;
  }

  private int rawDistance(String item, Set<String> ancestors) {
    if (inventory.getOrDefault(item, 0) > 0) return 0;
    if (ancestors.size() >= 4 || ancestors.contains(item)) return 20;
    if (item.endsWith("_LOG")
        || item.equals("COAL")
        || item.equals("COBBLESTONE")
        || item.equals("WHEAT")
        || item.endsWith("_WOOL")) return 2;
    var key = new Distance(item, ancestors);
    Integer known = distances.get(key);
    if (known != null) return known;
    var next = new HashSet<>(ancestors);
    next.add(item);
    Set<String> path = Set.copyOf(next);
    int best = Integer.MAX_VALUE;
    for (var recipe : recipes.getOrDefault(item, List.of())) {
      int total = 1;
      for (var slot : recipe.slots()) {
        int ingredient = Integer.MAX_VALUE;
        for (String choice : slot) ingredient = Math.min(ingredient, rawDistance(choice, path));
        total += ingredient == Integer.MAX_VALUE ? 20 : ingredient;
      }
      best = Math.min(best, total);
    }
    int result = best == Integer.MAX_VALUE ? 10 : best;
    distances.put(key, result);
    return result;
  }

  Map<String, Integer> cost(CraftingBook.Recipe recipe) {
    return costs.computeIfAbsent(recipe, this::calculateCost);
  }

  private Map<String, Integer> calculateCost(CraftingBook.Recipe recipe) {
    Map<String, Integer> result = new LinkedHashMap<>();
    for (var slot : recipe.slots()) {
      String best = null;
      int bestCost = Integer.MAX_VALUE;
      for (String item : slot) {
        int value =
            inventory.getOrDefault(item, 0) > result.getOrDefault(item, 0)
                ? -100
                : rawDistance(item, Set.of());
        if (best == null
            || value < bestCost
            || value == bestCost && item.startsWith("OAK_") && !best.startsWith("OAK_")) {
          best = item;
          bestCost = value;
        }
      }
      if (best == null) throw new IllegalArgumentException("Recipe ingredient has no choices");
      result.merge(best, 1, Integer::sum);
    }
    return Collections.unmodifiableMap(result);
  }
}
