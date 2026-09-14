package dev.civilizations.navigation;

import java.util.*;

/** A failed transition is reconsidered after its local block signature changes or a cooldown. */
public final class RouteMemory {
  private record Failure(String signature, long until) {}

  public record Saved(TerrainRouteSearch.Edge edge, String signature, long until) {}

  private final Map<TerrainRouteSearch.Edge, Failure> failures = new LinkedHashMap<>();

  public synchronized List<Saved> snapshot(long now) {
    failures.entrySet().removeIf(e -> e.getValue().until <= now);
    return failures.entrySet().stream()
        .map(e -> new Saved(e.getKey(), e.getValue().signature, e.getValue().until))
        .toList();
  }

  public synchronized void restore(List<Saved> values, long now) {
    for (Saved v : values)
      if (v.until > now && failures.size() < 256)
        failures.put(v.edge, new Failure(v.signature, v.until));
  }

  public synchronized void reject(TerrainRouteSearch.Edge edge, NavigationMap map, long now) {
    failures.put(edge, new Failure(signature(edge, map), now + 300_000));
    while (failures.size() > 256) failures.remove(failures.keySet().iterator().next());
  }

  public synchronized Set<TerrainRouteSearch.Edge> blocked(NavigationMap map, long now) {
    failures
        .entrySet()
        .removeIf(
            e ->
                e.getValue().until <= now
                    || map.contains(e.getKey().from())
                        && map.contains(e.getKey().to())
                        && !e.getValue().signature.equals(signature(e.getKey(), map)));
    return Set.copyOf(failures.keySet());
  }

  private static String signature(TerrainRouteSearch.Edge edge, NavigationMap map) {
    StringBuilder key = new StringBuilder();
    for (var p : List.of(edge.from(), edge.to()))
      for (int dy = -1; dy <= 2; dy++) key.append(map.cell(p.add(0, dy, 0))).append(';');
    return key.toString();
  }
}
