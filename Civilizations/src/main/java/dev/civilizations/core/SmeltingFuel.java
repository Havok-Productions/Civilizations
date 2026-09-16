package dev.civilizations.core;

import java.util.*;

/** Select carried renewable fuel after reserving the recipe's input. Actual burning is vanilla. */
public final class SmeltingFuel {
  private SmeltingFuel() {}

  public static boolean missing(
      boolean input,
      boolean output,
      boolean burning,
      boolean fuelInStation,
      Map<String, Integer> carried) {
    return input && !output && !burning && !fuelInStation && choose(carried, Map.of()) == null;
  }

  public static String choose(Map<String, Integer> inventory, Map<String, Integer> input) {
    return inventory.keySet().stream()
        .filter(m -> inventory.get(m) > input.getOrDefault(m, 0))
        .filter(
            m ->
                m.equals("COAL")
                    || m.equals("CHARCOAL")
                    || m.endsWith("_PLANKS")
                    || m.endsWith("_LOG") && !m.startsWith("STRIPPED_"))
        .sorted(
            Comparator.<String>comparingInt(
                    m ->
                        m.equals("COAL") || m.equals("CHARCOAL")
                            ? 0
                            : m.endsWith("_PLANKS") ? 1 : 2)
                .thenComparing(m -> m))
        .findFirst()
        .orElse(null);
  }
}
