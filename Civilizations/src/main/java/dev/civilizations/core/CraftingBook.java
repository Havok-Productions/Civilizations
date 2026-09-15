package dev.civilizations.core;

import java.util.*;

/** Immutable snapshot of server crafting recipes; planning never accesses a world or inventory. */
public final class CraftingBook {
  public record Recipe(
      String key,
      String output,
      int amount,
      List<List<String>> slots,
      boolean table,
      boolean furnace) {
    public Recipe(String key, String output, int amount, List<List<String>> slots, boolean table) {
      this(key, output, amount, slots, table, false);
    }

    public Recipe {
      slots = slots.stream().map(List::copyOf).toList();
    }
  }

  public record Step(
      String action,
      String item,
      int amount,
      Map<String, Integer> cost,
      String recipe,
      boolean table) {}

  private final Map<String, List<Recipe>> recipes;

  public CraftingBook(List<Recipe> source) {
    Map<String, List<Recipe>> map = new LinkedHashMap<>();
    for (Recipe r : source) map.computeIfAbsent(r.output(), k -> new ArrayList<>()).add(r);
    map.replaceAll((k, v) -> List.copyOf(v));
    recipes = Map.copyOf(map);
  }

  public Map<String, Integer> withdrawal(
      String output, Map<String, Integer> inv, Map<String, Integer> stock, boolean table) {
    return withdrawal(output, inv, stock, table, 1);
  }

  public Map<String, Integer> withdrawal(
      String output,
      Map<String, Integer> inv,
      Map<String, Integer> stock,
      boolean table,
      int demand) {
    int finished =
        Math.min(Math.max(0, demand - inv.getOrDefault(output, 0)), stock.getOrDefault(output, 0));
    if (finished > 0) return Map.of(output, finished);
    if (inv.getOrDefault(output, 0) > 0 || stock.isEmpty()) return Map.of();
    Map<String, Integer> combined = new HashMap<>(inv);
    stock.forEach((m, n) -> combined.merge(m, n, Integer::sum));
    Step step = next(output, combined, table);
    Map<String, Integer> result = new LinkedHashMap<>();
    step.cost()
        .forEach(
            (m, n) -> {
              int wanted =
                  Math.min(Math.max(0, n - inv.getOrDefault(m, 0)), stock.getOrDefault(m, 0));
              if (wanted > 0) result.put(m, wanted);
            });
    return Map.copyOf(result);
  }

  public int size() {
    return recipes.values().stream().mapToInt(List::size).sum();
  }

  /** Held ingredients committed to one immediate output, using the same recipe choices as work. */
  public Map<String, Integer> reserved(String output, Map<String, Integer> inventory) {
    Map<String, Integer> remaining = new HashMap<>(inventory), result = new HashMap<>();
    reserve(output, 1, remaining, result, new HashSet<>());
    return Map.copyOf(result);
  }

  private void reserve(
      String output,
      int amount,
      Map<String, Integer> inv,
      Map<String, Integer> result,
      Set<String> path) {
    int held = Math.min(amount, inv.getOrDefault(output, 0));
    if (held > 0) {
      result.merge(output, held, Integer::sum);
      inv.put(output, inv.get(output) - held);
    }
    if (held == amount || output.isEmpty() || !path.add(output)) return;
    Recipe recipe =
        recipes.getOrDefault(output, List.of()).stream()
            .min(Comparator.comparingInt(r -> score(r, inv)))
            .orElse(null);
    if (recipe == null) return;
    int batches = (amount - held + recipe.amount() - 1) / recipe.amount();
    cost(recipe, inv).forEach((m, n) -> reserve(m, n * batches, inv, result, new HashSet<>(path)));
    if (recipe.furnace()) {
      String fuel = SmeltingFuel.choose(inv, Map.of());
      if (fuel != null) reserve(fuel, 1, inv, result, new HashSet<>(path));
    }
  }

  public List<Recipe> snapshot() {
    return recipes.values().stream().flatMap(List::stream).toList();
  }

  public List<Recipe> furnaceRecipes(String output) {
    return recipes.getOrDefault(output, List.of()).stream().filter(Recipe::furnace).toList();
  }

  public Step next(String output, Map<String, Integer> inventory, boolean table) {
    return next(output, inventory, table, true);
  }

  public Step next(String output, Map<String, Integer> inventory, boolean table, boolean furnace) {
    return resolve(output, 1, inventory, table, furnace, new HashSet<>(), 0);
  }

  private Step resolve(
      String output,
      int amount,
      Map<String, Integer> inv,
      boolean table,
      boolean furnace,
      Set<String> path,
      int depth) {
    if (inv.getOrDefault(output, 0) >= amount)
      return new Step("ready", output, amount, Map.of(), "", false);
    if (depth >= 8 || !path.add(output)) return gather(output, amount);
    List<Recipe> choices = recipes.getOrDefault(output, List.of());
    // Prefer recipes whose actual ingredients we have, then basic raw-resource recipes.
    Recipe best = choices.stream().min(Comparator.comparingInt(r -> score(r, inv))).orElse(null);
    if (best == null) return gather(output, amount);
    if (best.furnace() && !furnace) {
      if (inv.getOrDefault("FURNACE", 0) > 0)
        return new Step("place_station", "FURNACE", 1, Map.of("FURNACE", 1), "", false);
      return resolve("FURNACE", 1, inv, table, false, new HashSet<>(path), depth + 1);
    }
    Map<String, Integer> cost = cost(best, inv);
    if (Set.of("COAL", "COBBLESTONE").contains(output)
        && cost.entrySet().stream().anyMatch(e -> inv.getOrDefault(e.getKey(), 0) < e.getValue()))
      return gather(output, amount);
    if (best.table() && !table) {
      if (inv.getOrDefault("CRAFTING_TABLE", 0) > 0)
        return new Step(
            "place_station", "CRAFTING_TABLE", 1, Map.of("CRAFTING_TABLE", 1), "", false);
      return resolve("CRAFTING_TABLE", 1, inv, false, furnace, new HashSet<>(path), depth + 1);
    }
    for (var e : cost.entrySet())
      if (inv.getOrDefault(e.getKey(), 0) < e.getValue())
        return resolve(
            e.getKey(), e.getValue(), inv, table, furnace, new HashSet<>(path), depth + 1);
    if (best.furnace()) {
      String fuel = SmeltingFuel.choose(inv, cost);
      if (fuel == null) return gather("OAK_LOG", cost.getOrDefault("OAK_LOG", 0) + 1);
      cost.merge(fuel, 1, Integer::sum);
      return new Step("smelt", output, best.amount(), Map.copyOf(cost), best.key(), false);
    }
    return new Step("craft", output, best.amount(), Map.copyOf(cost), best.key(), best.table());
  }

  private static Step gather(String item, int n) {
    return new Step("gather", item, n, Map.of(), "", false);
  }

  private int score(Recipe r, Map<String, Integer> inv) {
    int result = 0;
    for (var e : cost(r, inv).entrySet()) {
      int missing = Math.max(0, e.getValue() - inv.getOrDefault(e.getKey(), 0));
      if (missing > 0)
        result += 100 + rawDistance(e.getKey(), inv, new HashSet<>(), 0) * 10 + missing;
    }
    return result;
  }

  private int rawDistance(String item, Map<String, Integer> inv, Set<String> path, int depth) {
    if (inv.getOrDefault(item, 0) > 0) return 0;
    if (depth >= 4 || !path.add(item)) return 20;
    // Avoid recycling blocks (e.g. coal block -> coal) when the raw material is missing.
    if (item.endsWith("_LOG")
        || item.equals("COAL")
        || item.equals("COBBLESTONE")
        || item.equals("WHEAT")
        || item.endsWith("_WOOL")) return 2;
    return recipes.getOrDefault(item, List.of()).stream()
        .mapToInt(
            r ->
                1
                    + r.slots().stream()
                        .mapToInt(
                            slot ->
                                slot.stream()
                                    .mapToInt(
                                        s -> rawDistance(s, inv, new HashSet<>(path), depth + 1))
                                    .min()
                                    .orElse(20))
                        .sum())
        .min()
        .orElse(10);
  }

  private Map<String, Integer> cost(Recipe r, Map<String, Integer> inv) {
    Map<String, Integer> cost = new LinkedHashMap<>();
    for (List<String> slot : r.slots()) {
      String item =
          slot.stream()
              .min(
                  Comparator.<String>comparingInt(
                          s ->
                              inv.getOrDefault(s, 0) > cost.getOrDefault(s, 0)
                                  ? -100
                                  : rawDistance(s, inv, new HashSet<>(), 0))
                      .thenComparing(s -> !s.startsWith("OAK_")))
              .orElseThrow();
      cost.merge(item, 1, Integer::sum);
    }
    return cost;
  }
}
