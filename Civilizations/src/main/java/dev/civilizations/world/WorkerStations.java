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
  private final Material material;
  private final Set<Pos> known = new HashSet<>();
  private long nextScan;

  WorkerStations(Villager actor, Settlement village, WorkerNavigation navigation) {
    this(actor, village, navigation, Material.CRAFTING_TABLE);
  }

  WorkerStations(
      Villager actor, Settlement village, WorkerNavigation navigation, Material material) {
    this.actor = actor;
    this.village = village;
    this.navigation = navigation;
    this.material = material;
  }

  void placed(Pos p) {
    known.add(p);
  }

  Pos choose(Pos at, long now) {
    Pos shared = material == Material.CRAFTING_TABLE ? village.craftingTable() : null;
    if (shared != null) known.add(shared);
    known.addAll(navigation.observed(material.name()));
    village.repairBlocks().stream()
        .filter(b -> b.material().equals(material.name()))
        .forEach(b -> known.add(b.position()));
    if (now >= nextScan) {
      nextScan = now + 10_000;
      for (int x = -4; x <= 4; x++)
        for (int z = -4; z <= 4; z++)
          for (int y = -2; y <= 2; y++) {
            Pos p = at.add(x, y, z);
            Location loc = location(p);
            if (Bukkit.isOwnedByCurrentRegion(loc, 1) && loc.getBlock().getType() == material)
              known.add(p);
          }
    }
    known.removeIf(
        p ->
            Bukkit.isOwnedByCurrentRegion(location(p), 1)
                && location(p).getBlock().getType() != material);
    // Keep the cache bounded even as this worker travels between distant projects.
    if (known.size() > 64) {
      List<Pos> nearest =
          known.stream().sorted(Comparator.comparingLong(p -> p.distance2(at))).limit(64).toList();
      known.retainAll(nearest);
    }
    return known.stream()
        .filter(
            p ->
                !village
                    .knowledge()
                    .blocked("station_busy:" + material.name() + ":" + p.key(), now))
        .filter(p -> at.distance2(p) <= 12 || !village.knowledge().blocked("route:" + p.key(), now))
        .min(Comparator.comparingLong(p -> p.distance2(at)))
        .orElse(null);
  }

  private Location location(Pos p) {
    return new Location(actor.getWorld(), p.x(), p.y(), p.z());
  }
}
