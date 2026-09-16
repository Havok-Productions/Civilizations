package dev.civilizations.design;

import dev.civilizations.core.Pos;
import dev.civilizations.world.Terrain;
import java.util.*;

/** Rejects disconnected islands and cliff-top sites before villagers are assigned work. */
final class DesignAccess {
  static void verify(DesignSite s) {
    if (s.jobs.getFirst().kind == dev.civilizations.core.Job.Kind.MINE) {
      var entrance = s.jobs.getFirst().stand;
      s.require(
          reachable(s, s.prepared, List.of(entrance), false).contains(key(entrance)),
          "Mine entrance is disconnected from village walking ground");
      return;
    }
    // A house's floor creates real standing space one level above the original ground.
    // Validate each stage against preceding work, never equate it with the floor below.
    Map<Pos, String> staged = new HashMap<>(s.prepared);
    for (var j : s.jobs) {
      if (j.kind == dev.civilizations.core.Job.Kind.CLEAR) continue;
      s.require(
          reachable(s, staged, List.of(j.stand), false).contains(key(j.stand)),
          "Design work site is disconnected by water, structures or cliffs at " + j.stand.key());
      if (j.kind == dev.civilizations.core.Job.Kind.PLACE) staged.put(j.target, j.material);
      else if (j.kind == dev.civilizations.core.Job.Kind.PATH) staged.put(j.target, "DIRT_PATH");
    }
  }

  /** Search only until the requested work positions (or one next clearing approach) are reached. */
  static Set<String> reachable(
      DesignSite s, Map<Pos, String> changes, Collection<Pos> targets, boolean any) {
    Set<String> pending = new HashSet<>();
    targets.forEach(p -> pending.add(key(p)));
    if (pending.isEmpty()) return Set.of();
    Terrain terrain =
        new Terrain() {
          public int height(int x, int z) {
            return s.terrain.height(x, z);
          }

          public boolean available(int x, int z) {
            return s.terrain.available(x, z);
          }

          public String type(Pos p) {
            return changes.getOrDefault(p, s.terrain.type(p));
          }

          public boolean clear(Pos p) {
            return changes.containsKey(p) ? Terrain.super.clear(p) : s.terrain.clear(p);
          }

          public String blockData(Pos p) {
            return s.terrain.blockData(p);
          }
        };
    Set<String> reached = new HashSet<>();
    // Visit cells nearest a requested stand first. Exhaustion still explores the same 3D graph,
    // but staged construction need not flood an entire distant village for every single block.
    Queue<Pos> queue =
        new PriorityQueue<>(
            Comparator.comparingLong(
                p -> targets.stream().mapToLong(p::distance2).min().orElse(0)));
    Pos c = s.center;
    int extent =
        java.util.stream.Stream.concat(
                    targets.stream(),
                    s.jobs.stream().flatMap(j -> java.util.stream.Stream.of(j.target, j.stand)))
                .mapToInt(
                    j ->
                        (int)
                            Math.min(
                                Integer.MAX_VALUE - 2,
                                Math.max(
                                    Math.abs((long) j.x() - c.x()),
                                    Math.abs((long) j.z() - c.z()))))
                .max()
                .orElse(0)
            + 2;
    extent = Math.max(26, extent);
    outer:
    for (int r = 0; r <= 4; r++)
      for (int x = -r; x <= r; x++)
        for (int z = -r; z <= r; z++) {
          List<Pos> starts = walk(terrain, c.x() + x, c.z() + z, c.y(), 3);
          if (!starts.isEmpty()) {
            Pos p = starts.getFirst();
            queue.add(p);
            reached.add(key(p));
            if (pending.remove(key(p)) && (any || pending.isEmpty())) return reached;
            break outer;
          }
        }
    s.require(!queue.isEmpty(), "Cannot identify village walking ground near the center");
    while (!queue.isEmpty()) {
      Pos p = queue.remove();
      for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
        int x = p.x() + d[0], z = p.z() + d[1];
        if (Math.abs((long) x - c.x()) > extent || Math.abs((long) z - c.z()) > extent) continue;
        for (Pos q : walk(terrain, x, z, p.y(), 1))
          if (reached.add(key(q))) {
            if (pending.remove(key(q)) && (any || pending.isEmpty())) return reached;
            s.require(
                reached.size() < 20000,
                "Access search incomplete after "
                    + reached.size()
                    + " walking cells; still seeking "
                    + pending.stream().sorted().limit(4).toList()
                    + "; this is a search budget limit, not proof of disconnected terrain");
            queue.add(q);
          }
      }
    }
    return reached;
  }

  static String key(Pos p) {
    return p.key();
  }

  private static List<Pos> walk(Terrain t, int x, int z, int nearY, int range) {
    if (!t.available(x, z)) return List.of();
    List<Pos> result = new ArrayList<>();
    // A heightmap reports roofs and tree tops. Navigation needs the walking floor beneath them.
    for (int offset = 0; offset <= range; offset++)
      for (int direction : new int[] {1, -1}) {
        Pos feet = new Pos(x, nearY + offset * direction, z), ground = feet.add(0, -1, 0);
        String support = t.type(ground);
        if (offset == 0 && direction == -1) continue;
        if ((t.natural(ground)
                || support.endsWith("_PLANKS")
                || support.endsWith("_STAIRS")
                || support.endsWith("_SLAB")
                || Set.of("DIRT_PATH", "FARMLAND", "COBBLESTONE", "STONE_BRICKS").contains(support))
            && walkThrough(t, feet)
            && walkThrough(t, feet.add(0, 1, 0))
            && !t.fluid(ground)) result.add(feet);
      }
    return result;
  }

  private static boolean walkThrough(Terrain t, Pos p) {
    String type = t.type(p);
    return t.clear(p)
        || type.endsWith("_FENCE_GATE")
        || type.endsWith("_DOOR") && !type.equals("IRON_DOOR");
  }
}
