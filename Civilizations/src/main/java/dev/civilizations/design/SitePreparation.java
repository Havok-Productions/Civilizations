package dev.civilizations.design;

import dev.civilizations.core.*;
import dev.civilizations.world.NavigationTerrain;
import java.util.*;

/** Compile real clearing stages against the terrain produced by preceding stages. */
final class SitePreparation {
  static void house(DesignSite s, Blueprint b) {
    List<Pos> surface = new ArrayList<>();
    for (int x = -1; x <= b.width(); x++)
      for (int z = -1; z <= b.depth(); z++) surface.add(s.ground(b.x() + x, b.z() + z));
    int floor = surface.stream().mapToInt(Pos::y).min().orElseThrow();
    Set<Pos> targets = new HashSet<>();
    for (Pos p : surface) {
      Pos foundation = new Pos(p.x(), floor, p.z());
      s.require(
          s.terrain.natural(foundation) && s.terrain.dry(foundation),
          "Grading lacks observed dry natural foundation at " + foundation.key());
      for (int y = floor + 1; y <= Math.max(p.y(), floor + b.height() + 1); y++)
        targets.add(new Pos(p.x(), y, p.z()));
    }
    clear(s, targets);
  }

  static void route(DesignSite s, List<Blueprint.Point> points, int height) {
    Set<Pos> targets = new HashSet<>();
    for (Blueprint.Point point : points) {
      Pos ground = s.ground(point.x(), point.z());
      for (int y = 1; y <= height; y++) targets.add(ground.add(0, y, 0));
    }
    clear(s, targets);
  }

  private static void clear(DesignSite s, Set<Pos> volume) {
    List<Pos> remaining =
        volume.stream()
            .filter(p -> !Set.of("AIR", "CAVE_AIR", "VOID_AIR").contains(s.type(p)))
            .sorted(Comparator.comparingInt(Pos::y).reversed().thenComparing(Pos::key))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    for (Pos p : remaining) {
      s.require(
          !s.occupied.test(p), "Site preparation intersects a protected structure at " + p.key());
      s.require(
          SiteMaterials.vegetation(s.type(p)) || NavigationTerrain.salvageable(s.terrain, p),
          "Site obstacle needs classification or another layout at "
              + p.key()
              + " ("
              + s.type(p)
              + ")");
      s.require(s.terrain.dry(p), "Site preparation meets water or unknown terrain at " + p.key());
    }
    while (!remaining.isEmpty()) {
      boolean advanced = false;
      Set<String> accessible = DesignAccess.reachable(s, s.prepared, volume);
      for (Pos p : List.copyOf(remaining)) {
        String above = s.type(p.add(0, 1, 0));
        boolean tree = s.type(p).endsWith("_LOG") || s.type(p).endsWith("_LEAVES");
        if (!s.clear(p.add(0, 1, 0))
            && !(tree && (above.endsWith("_LOG") || above.endsWith("_LEAVES")))) continue;
        Pos stand;
        try {
          stand = s.standNear(p);
        } catch (IllegalArgumentException unavailable) {
          continue;
        }
        if (!accessible.contains(DesignAccess.key(stand))) continue;
        s.add(Job.Kind.CLEAR, p, stand, "", tree ? "natural-tree" : null, s.jobs.size());
        remaining.remove(p);
        advanced = true;
        break;
      }
      s.require(
          advanced,
          "Preparation needs an approach or scaffold before clearing "
              + remaining.stream().limit(4).map(Pos::key).toList());
    }
  }
}
