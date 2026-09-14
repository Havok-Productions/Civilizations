package dev.civilizations.core;

import java.util.*;

/** Decrease an encounter's request after receipts so a second donor cannot oversupply it. */
public final class SupplyRequests {
  private SupplyRequests() {}

  public static Map<String, Integer> remaining(
      Map<String, Integer> requested, Map<String, Integer> before, Map<String, Integer> after) {
    Map<String, Integer> result = new LinkedHashMap<>();
    requested.forEach(
        (resource, n) -> {
          int received =
              after.entrySet().stream()
                  .filter(
                      e ->
                          resource.equals(e.getKey())
                              || resource.equals("LOG") && e.getKey().endsWith("_LOG"))
                  .mapToInt(e -> Math.max(0, e.getValue() - before.getOrDefault(e.getKey(), 0)))
                  .sum();
          if (n > received) result.put(resource, n - received);
        });
    return Map.copyOf(result);
  }
}
