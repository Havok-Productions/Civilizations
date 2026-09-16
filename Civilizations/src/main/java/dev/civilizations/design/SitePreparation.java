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
    List<Pos> ground = points.stream().map(p -> s.ground(p.x(), p.z())).toList();
    int[] levels = ground.stream().mapToInt(Pos::y).toArray();
    // Grade soil humps into one-block rises. Open path endpoints are not adjacent contour columns.
    boolean changed;
    do {
      changed = false;
      for (int i = 0; i < levels.length; i++) {
        int j = (i + 1) % levels.length;
        Pos a = ground.get(i), b = ground.get(j);
        if (Math.abs((long) a.x() - b.x()) + Math.abs((long) a.z() - b.z()) != 1) continue;
        if ((long) levels[i] > (long) levels[j] + 1) {
          levels[i] = levels[j] + 1;
          changed = true;
        }
        if ((long) levels[j] > (long) levels[i] + 1) {
          levels[j] = levels[i] + 1;
          changed = true;
        }
      }
    } while (changed);
    Set<Pos> targets = new HashSet<>();
    for (int i = 0; i < points.size(); i++) {
      Pos column = ground.get(i), foundation = new Pos(column.x(), levels[i], column.z());
      s.require(
          s.terrain.natural(foundation) && s.terrain.dry(foundation),
          "Route grading lacks dry natural support at "
              + foundation.key()
              + " (support="
              + s.terrain.type(foundation)
              + "; nearby="
              + surroundings(s, foundation)
              + ")");
      for (int y = levels[i] + 1; y <= column.y() + height; y++)
        targets.add(new Pos(column.x(), y, column.z()));
    }
    clear(s, targets);
  }

  private static Map<String, List<String>> surroundings(DesignSite s, Pos at) {
    Map<String, List<String>> result = new TreeMap<>();
    for (int x = -1; x <= 1; x++)
      for (int y = -1; y <= 1; y++)
        for (int z = -1; z <= 1; z++) {
          Pos p = at.add(x, y, z);
          if (s.terrain.fluid(p) || s.terrain.type(p).equals("UNKNOWN"))
            result.computeIfAbsent(s.terrain.type(p), k -> new ArrayList<>()).add(p.key());
        }
    return result;
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
          SiteMaterials.vegetation(s.type(p))
              || NavigationTerrain.salvageable(s.terrain, p)
              || mineable(s, p),
          "Site obstacle needs classification or another layout at "
              + p.key()
              + " ("
              + s.type(p)
              + ")");
      s.require(s.terrain.dry(p), "Site preparation meets water or unknown terrain at " + p.key());
    }
    while (!remaining.isEmpty()) {
      boolean advanced = false;
      Map<Pos, Pos> approaches = new LinkedHashMap<>();
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
        approaches.put(p, stand);
      }
      // One reachable next stage is enough. Obstructed later stages become accessible after
      // earlier clearing; they must not force an exhaustive flood of unrelated open terrain.
      Set<String> accessible = DesignAccess.reachable(s, s.prepared, approaches.values(), true);
      for (var approach : approaches.entrySet()) {
        Pos p = approach.getKey(), stand = approach.getValue();
        if (!accessible.contains(DesignAccess.key(stand))) continue;
        boolean tree = s.type(p).endsWith("_LOG") || s.type(p).endsWith("_LEAVES");
        s.add(
            dev.civilizations.core.HarvestCatalog.mineral(s.type(p))
                ? Job.Kind.MINE
                : Job.Kind.CLEAR,
            p,
            stand,
            "",
            tree ? "natural-tree" : null,
            s.jobs.size());
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

  private static boolean mineable(DesignSite s, Pos p) {
    return dev.civilizations.core.HarvestCatalog.mineral(s.type(p))
        && !s.occupied.test(p)
        && !NavigationTerrain.architectureNear(s.terrain, p, Set.of());
  }
}
