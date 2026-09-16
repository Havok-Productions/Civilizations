package dev.civilizations.world;

import dev.civilizations.core.*;
import dev.civilizations.navigation.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.World;

/** Shares bounded snapshot/search admission and failed transition memory across villagers. */
public final class NavigationService implements AutoCloseable {
  public record Plan(
      String id,
      String file,
      NavigationMap map,
      TerrainRouteSearch.Result route,
      Pos target,
      int reach2,
      long capturedAt,
      List<RouteMemory.Saved> remembered) {}

  private final RegionSnapshots snapshots;
  private final Executor executor;
  private final NavigationArchive archive;

  private record Lane(String worker, String purpose) {}

  private record Request(World world, Settlement village, Pos from, Pos target, int reach) {}

  private final NavigationQueue<Lane, Plan> admission = new NavigationQueue<>(4);
  private final ConcurrentHashMap<String, RouteMemory> memories = new ConcurrentHashMap<>();
  private final int radius;
  private final boolean salvage;
  private volatile dev.coreai.TerrainRuleBook rules;

  public void rules(dev.coreai.TerrainRuleBook rules, int maximum) {
    rules(rules);
  }

  public void rules(dev.coreai.TerrainRuleBook rules) {
    this.rules = rules;
  }

  public int radiusFor(String worker) {
    return rules == null ? radius : rules.radius(worker, radius, 0);
  }

  public NavigationService(
      RegionSnapshots snapshots,
      Executor executor,
      NavigationArchive archive,
      int radius,
      boolean salvage) {
    this.snapshots = snapshots;
    this.executor = executor;
    this.archive = archive;
    this.radius = radius;
    this.salvage = salvage;
    archive.loadMemory().entrySet().stream()
        .limit(128)
        .forEach(
            e -> {
              RouteMemory m = new RouteMemory();
              m.restore(e.getValue(), System.currentTimeMillis());
              memories.put(e.getKey(), m);
            });
  }

  private synchronized RouteMemory memory(String village) {
    if (!memories.containsKey(village) && memories.size() >= 128)
      memories.remove(memories.keys().nextElement());
    return memories.computeIfAbsent(village, k -> new RouteMemory());
  }

  public CompletableFuture<Plan> request(
      World world, Settlement village, String worker, Pos from, Pos target, int reach2) {
    return request(world, village, worker, from, target, reach2, "movement");
  }

  public CompletableFuture<Plan> request(
      World world,
      Settlement village,
      String worker,
      Pos from,
      Pos target,
      int reach2,
      String purpose) {
    return admission.submit(
        new Lane(worker, purpose),
        new Request(world, village, from, target, reach2),
        () -> capture(world, village, worker, from, target, reach2));
  }

  public void cancel(String worker, String purpose) {
    admission.cancel(new Lane(worker, purpose));
  }

  private CompletableFuture<Plan> capture(
      World world, Settlement village, String worker, Pos from, Pos target, int reach2) {
    try {
      int searchRadius = radiusFor(worker);
      if (searchRadius < 0)
        throw new IllegalArgumentException("Negative radius has no geometric meaning");
      long side = 2L * searchRadius + 1;
      if (side > 2_000_000L / 24 / side)
        throw new RejectedExecutionException(
            "snapshot_resource_budget_exceeded: proposed radius="
                + searchRadius
                + "; use successive loaded maps or propose a cheaper search");
      Set<Pos> protectedBlocks = new HashSet<>(village.layoutOccupancy());
      village
          .snapshot()
          .playerBlocks
          .forEach(
              s -> {
                String[] xyz = s.split(",");
                if (xyz.length == 3)
                  try {
                    protectedBlocks.add(
                        new Pos(
                            Integer.parseInt(xyz[0]),
                            Integer.parseInt(xyz[1]),
                            Integer.parseInt(xyz[2])));
                  } catch (NumberFormatException ignored) {
                  }
              });
      RouteMemory memory = memory(village.id());
      return snapshots
          .capture(world, from, searchRadius)
          .thenApplyAsync(
              terrain -> {
                NavigationMap map =
                    NavigationTerrain.capture(
                        terrain, from, searchRadius, protectedBlocks, rules, worker);
                long capturedAt = System.currentTimeMillis();
                var remembered = memory.active(worker, map, capturedAt);
                Set<TerrainRouteSearch.Edge> rejected = new HashSet<>();
                remembered.forEach(v -> rejected.add(v.edge()));
                TerrainRouteSearch.Result route =
                    TerrainRouteSearch.search(
                        map, from, target, reach2, rejected, 0, searchBudget(searchRadius));
                if (route.steps().isEmpty() && !route.reached() && salvage)
                  route =
                      TerrainRouteSearch.search(
                          map,
                          from,
                          target,
                          reach2,
                          rejected,
                          rules == null
                              ? 16
                              : rules.parameter(worker, "navigation.clearance_blocks", 16),
                          searchBudget(searchRadius));
                String id = UUID.randomUUID().toString(),
                    file = archive.save(id, village.id(), worker, map, route);
                return new Plan(id, file, map, route, target, reach2, capturedAt, remembered);
              },
              executor);
    } catch (RuntimeException error) {
      // Keep the asynchronous contract: callers clear pending state and roll back trials here.
      return CompletableFuture.failedFuture(error);
    }
  }

  public RouteMemory.Saved reject(
      String village,
      String worker,
      TerrainRouteSearch.Edge edge,
      NavigationMap map,
      String reason,
      long now,
      long retryMillis) {
    var failure = memory(village).reject(worker, edge, map, reason, now, retryMillis);
    persist();
    return failure;
  }

  private void persist() {
    Map<String, List<RouteMemory.Saved>> values = new HashMap<>();
    long now = System.currentTimeMillis();
    memories.forEach(
        (k, v) -> {
          var entries = v.snapshot(now);
          if (!entries.isEmpty()) values.put(k, entries);
        });
    archive.saveMemory(Map.copyOf(values));
  }

  public static int searchBudget(int radius) {
    return Math.clamp((long) radius * radius * 30, 12000, 48000);
  }

  public int radius() {
    return radiusFor("");
  }

  public long droppedMaps() {
    return archive.dropped();
  }

  public void close() {
    admission.close();
    persist();
    archive.close();
    memories.clear();
  }
}
