package dev.civilizations.navigation;

import java.util.*;

/** Actor-local retry evidence. A native path refusal is not proof a village route is impossible. */
public final class RouteMemory {
  public record Saved(
      TerrainRouteSearch.Edge edge,
      String signature,
      long until,
      String worker,
      String reason,
      long observedAt) {}

  private record Key(String worker, TerrainRouteSearch.Edge edge) {}

  private final Map<Key, Saved> failures = new LinkedHashMap<>();

  public synchronized List<Saved> snapshot(long now) {
    failures.values().removeIf(v -> v.until <= now);
    return List.copyOf(failures.values());
  }

  public synchronized void restore(List<Saved> values, long now) {
    for (Saved v : values)
      // Old village-wide bans lacked actor/reason/state evidence; do not revive those bans.
      if (v.worker != null
          && v.reason != null
          && v.signature != null
          && v.edge != null
          && v.until > now
          && failures.size() < 256) failures.put(new Key(v.worker, v.edge), v);
  }

  public synchronized Saved reject(
      String worker,
      TerrainRouteSearch.Edge edge,
      NavigationMap map,
      String reason,
      long now,
      long retryMillis) {
    Saved failure =
        new Saved(edge, signature(edge, map), now + Math.max(0, retryMillis), worker, reason, now);
    failures.put(new Key(worker, edge), failure);
    while (failures.size() > 256) failures.remove(failures.keySet().iterator().next());
    return failure;
  }

  public synchronized List<Saved> active(String worker, NavigationMap map, long now) {
    failures
        .values()
        .removeIf(
            v ->
                v.until <= now
                    || map.contains(v.edge.from())
                        && map.contains(v.edge.to())
                        && !v.signature.equals(signature(v.edge, map)));
    return failures.values().stream().filter(v -> v.worker.equals(worker)).toList();
  }

  public synchronized Set<TerrainRouteSearch.Edge> blocked(
      String worker, NavigationMap map, long now) {
    Set<TerrainRouteSearch.Edge> result = new HashSet<>();
    active(worker, map, now).forEach(v -> result.add(v.edge));
    return Set.copyOf(result);
  }

  private static String signature(TerrainRouteSearch.Edge edge, NavigationMap map) {
    StringBuilder key = new StringBuilder();
    for (var p : List.of(edge.from(), edge.to()))
      for (int dx = -1; dx <= 1; dx++)
        for (int dz = -1; dz <= 1; dz++)
          for (int dy = -1; dy <= 2; dy++) key.append(map.cell(p.add(dx, dy, dz))).append(';');
    return key.toString();
  }
}
