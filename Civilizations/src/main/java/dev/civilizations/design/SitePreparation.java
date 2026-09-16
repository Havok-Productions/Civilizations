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
    surface.forEach(p -> s.footprint.add(p.x() + "," + p.z()));
    Set<Pos> targets = new HashSet<>();
    Set<Pos> fill = new HashSet<>();
    for (Pos p : surface) {
      Pos foundation = new Pos(p.x(), floor, p.z());
      SiteFoundations.collect(s, foundation, targets, fill);
      for (int y = floor + 1; y <= Math.max(p.y(), floor + b.height() + 1); y++)
        targets.add(new Pos(p.x(), y, p.z()));
    }
    prepare(s, targets, fill);
    for (Pos p : surface) s.grades.put(p.x() + "," + p.z(), floor);
  }

  static void route(DesignSite s, List<Blueprint.Point> points, int height) {
    List<Pos> ground = points.stream().map(p -> s.ground(p.x(), p.z())).toList();
    ground.forEach(p -> s.footprint.add(p.x() + "," + p.z()));
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
    Set<Pos> fill = new HashSet<>();
    for (int i = 0; i < points.size(); i++) {
      Pos column = ground.get(i), foundation = new Pos(column.x(), levels[i], column.z());
      SiteFoundations.collect(s, foundation, targets, fill);
      for (int y = levels[i] + 1; y <= column.y() + height; y++)
        targets.add(new Pos(column.x(), y, column.z()));
    }
    prepare(s, targets, fill);
    for (int i = 0; i < ground.size(); i++) {
      Pos p = ground.get(i);
      s.grades.put(p.x() + "," + p.z(), levels[i]);
    }
  }

  static void prepare(DesignSite s, Set<Pos> volume, Set<Pos> fill) {
    prepare(s, volume, fill, true);
  }

  static void prepare(DesignSite s, Set<Pos> volume, Set<Pos> fill, boolean buildAccess) {
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
              || SiteMaterials.earthwork(s.type(p))
              || NavigationTerrain.salvageable(s.terrain, p)
              || mineable(s, p),
          "Site obstacle needs classification or another layout at "
              + p.key()
              + " ("
              + s.type(p)
              + ")");
      s.require(s.terrain.dry(p), "Site preparation meets water or unknown terrain at " + p.key());
    }
    List<Pos> filling =
        fill.stream()
            .sorted(Comparator.comparingInt(Pos::y).thenComparing(Pos::key))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    while (!remaining.isEmpty() || !filling.isEmpty()) {
      boolean advanced = false;
      Map<Pos, List<Pos>> approaches = new LinkedHashMap<>();
      for (Pos p : List.copyOf(remaining)) {
        String above = s.type(p.add(0, 1, 0));
        boolean tree = s.type(p).endsWith("_LOG") || s.type(p).endsWith("_LEAVES");
        if (!s.clear(p.add(0, 1, 0))
            && !(tree && (above.endsWith("_LOG") || above.endsWith("_LEAVES")))) continue;
        approaches.put(p, s.standsNear(p));
      }
      for (Pos p : filling)
        if (!remaining.contains(p) && s.clear(p) && s.solid(p.add(0, -1, 0)))
          approaches.put(p, s.standsNear(p));
      // One reachable next stage is enough. Obstructed later stages become accessible after
      // earlier clearing; they must not force an exhaustive flood of unrelated open terrain.
      Set<String> accessible =
          DesignAccess.reachable(
              s, s.placed, approaches.values().stream().flatMap(Collection::stream).toList(), true);
      boolean provenApproach =
          approaches.values().stream()
              .flatMap(Collection::stream)
              .anyMatch(p -> accessible.contains(DesignAccess.key(p)));
      for (var approach : approaches.entrySet()) {
        Pos p = approach.getKey(),
            stand =
                approach.getValue().stream()
                    .filter(q -> accessible.contains(DesignAccess.key(q)))
                    .findFirst()
                    .orElse(null);
        if (stand == null) {
          if (provenApproach || s.trialWarnings == null || approach.getValue().isEmpty()) continue;
          stand = approach.getValue().getFirst();
          s.trialWarnings.add(
              "Unproven preparation approach to " + p.key() + " from " + stand.key());
        }
        boolean placing = !remaining.contains(p) && filling.contains(p);
        boolean tree = s.type(p).endsWith("_LOG") || s.type(p).endsWith("_LEAVES");
        s.add(
            placing
                ? Job.Kind.PLACE
                : dev.civilizations.core.HarvestCatalog.mineral(s.type(p))
                    ? Job.Kind.MINE
                    : Job.Kind.CLEAR,
            p,
            stand,
            placing ? "COBBLESTONE" : "",
            tree ? "natural-tree" : null,
            s.jobs.size());
        if (placing) {
          filling.remove(p);
          s.prepared.put(p, "COBBLESTONE");
        } else remaining.remove(p);
        advanced = true;
        break;
      }
      List<String> accessFailures = new ArrayList<>();
      if (!advanced && buildAccess) {
        for (Pos p : remaining) {
          if (s.standsNear(p).isEmpty() && SiteAccessSteps.build(s, p, accessFailures)) {
            advanced = true;
            break;
          }
        }
      }
      s.require(
          advanced,
          "Preparation needs an approach or scaffold before clearing "
              + remaining.stream().limit(4).map(Pos::key).toList()
              + "; pending_fill="
              + filling.stream().limit(4).map(Pos::key).toList()
              + "; observed_approaches="
              + approaches.values().stream().mapToInt(List::size).sum()
              + "; access_step_failures="
              + accessFailures.stream().limit(4).toList());
    }
  }

  private static boolean mineable(DesignSite s, Pos p) {
    return dev.civilizations.core.HarvestCatalog.mineral(s.type(p))
        && !s.occupied.test(p)
        && !NavigationTerrain.architectureNear(s.terrain, p, Set.of());
  }
}
