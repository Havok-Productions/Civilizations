package dev.civilizations.design;

import dev.civilizations.core.Pos;
import dev.civilizations.world.Terrain;
import java.util.*;

/** Rejects disconnected islands and cliff-top sites before villagers are assigned work. */
final class DesignAccess {
  static void verify(DesignSite s) {
    Set<String> reached = new HashSet<>();
    ArrayDeque<Pos> queue = new ArrayDeque<>();
    Pos c = s.center;
    int extent =
        s.jobs.stream()
                .mapToInt(
                    j ->
                        (int)
                            Math.min(
                                Integer.MAX_VALUE - 2,
                                Math.max(
                                    Math.abs((long) j.stand.x() - c.x()),
                                    Math.abs((long) j.stand.z() - c.z()))))
                .max()
                .orElse(0)
            + 2;
    extent = Math.max(26, extent);
    outer:
    for (int r = 0; r <= 4; r++)
      for (int x = -r; x <= r; x++)
        for (int z = -r; z <= r; z++) {
          Pos p = walk(s.terrain, c.x() + x, c.z() + z, c.y(), 3);
          if (p != null && Math.abs(p.y() - c.y()) <= 3) {
            queue.add(p);
            reached.add(key(p));
            break outer;
          }
        }
    s.require(!queue.isEmpty(), "Cannot identify village walking ground near the center");
    while (!queue.isEmpty()) {
      Pos p = queue.remove();
      for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
        int x = p.x() + d[0], z = p.z() + d[1];
        if (Math.abs((long) x - c.x()) > extent || Math.abs((long) z - c.z()) > extent) continue;
        s.require(
            reached.size() < 20000,
            "Access-search work budget exhausted; divide the proposed project into stages");
        Pos q = walk(s.terrain, x, z, p.y(), 1);
        if (q != null && Math.abs(q.y() - p.y()) <= 1 && reached.add(key(q))) queue.add(q);
      }
    }
    if (s.jobs.getFirst().kind == dev.civilizations.core.Job.Kind.MINE)
      s.require(
          reached.contains(key(s.jobs.getFirst().stand)),
          "Mine entrance is disconnected from village walking ground");
    else
      for (var j : s.jobs)
        s.require(
            reached.contains(key(j.stand)),
            "Design work site is disconnected by water, structures or cliffs at " + j.stand.key());
  }

  private static String key(Pos p) {
    return p.x() + "," + p.z();
  }

  private static Pos walk(Terrain t, int x, int z, int nearY, int range) {
    if (!t.available(x, z)) return null;
    // A heightmap reports roofs and tree tops. Navigation needs the walking floor beneath them.
    for (int offset = 0; offset <= range; offset++)
      for (int direction : new int[] {1, -1}) {
        Pos feet = new Pos(x, nearY + offset * direction, z), ground = feet.add(0, -1, 0);
        String support = t.type(ground);
        if ((t.natural(ground)
                || support.endsWith("_PLANKS")
                || support.endsWith("_STAIRS")
                || support.endsWith("_SLAB")
                || Set.of("DIRT_PATH", "FARMLAND", "COBBLESTONE", "STONE_BRICKS").contains(support))
            && walkThrough(t, feet)
            && walkThrough(t, feet.add(0, 1, 0))
            && !t.fluid(ground)) return feet;
      }
    return null;
  }

  private static boolean walkThrough(Terrain t, Pos p) {
    String type = t.type(p);
    return t.clear(p)
        || type.endsWith("_FENCE_GATE")
        || type.endsWith("_DOOR") && !type.equals("IRON_DOOR");
  }
}
