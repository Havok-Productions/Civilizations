package dev.civilizations.world;

import dev.civilizations.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Villager;

/** Per-worker station choice; an inaccessible shared station does not block local crafting. */
final class WorkerStations {
  private final Villager actor;
  private final Settlement village;
  private final WorkerNavigation navigation;
  private final Set<Pos> known = new HashSet<>();
  private long nextScan;

  WorkerStations(Villager actor, Settlement village, WorkerNavigation navigation) {
    this.actor = actor;
    this.village = village;
    this.navigation = navigation;
  }

  void placed(Pos p) {
    known.add(p);
  }

  Pos choose(Pos at, long now) {
    Pos shared = village.craftingTable();
    if (shared != null) known.add(shared);
    known.addAll(navigation.observed("CRAFTING_TABLE"));
    if (now >= nextScan) {
      nextScan = now + 10_000;
      for (int x = -4; x <= 4; x++)
        for (int z = -4; z <= 4; z++)
          for (int y = -2; y <= 2; y++) {
            Pos p = at.add(x, y, z);
            Location loc = location(p);
            if (Bukkit.isOwnedByCurrentRegion(loc, 1)
                && loc.getBlock().getType() == Material.CRAFTING_TABLE) known.add(p);
          }
    }
    known.removeIf(
        p ->
            Bukkit.isOwnedByCurrentRegion(location(p), 1)
                && location(p).getBlock().getType() != Material.CRAFTING_TABLE);
    // Keep the cache bounded even as this worker travels between distant projects.
    if (known.size() > 64) {
      List<Pos> nearest =
          known.stream().sorted(Comparator.comparingLong(p -> p.distance2(at))).limit(64).toList();
      known.retainAll(nearest);
    }
    return known.stream()
        .filter(p -> at.distance2(p) <= 12 || !village.knowledge().blocked("route:" + p.key(), now))
        .min(Comparator.comparingLong(p -> p.distance2(at)))
        .orElse(null);
  }

  private Location location(Pos p) {
    return new Location(actor.getWorld(), p.x(), p.y(), p.z());
  }
}
