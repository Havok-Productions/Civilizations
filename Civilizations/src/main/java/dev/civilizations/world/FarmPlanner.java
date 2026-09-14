package dev.civilizations.world;

import dev.civilizations.core.*;
import java.util.*;

/** Small hydrated wheat plots; no generated water, distant work, or terrain excavation. */
public final class FarmPlanner {
  public List<Job> plan(Terrain terrain, Pos center) {
    List<Job> result = new ArrayList<>();
    for (int r = 0; r <= 12 && result.size() < 9; r++)
      for (int dx = -r; dx <= r && result.size() < 9; dx++)
        for (int dz = -r; dz <= r && result.size() < 9; dz++) {
          if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
          int x = center.x() + dx, z = center.z() + dz;
          if (!terrain.available(x, z)) continue;
          int h = terrain.height(x, z);
          Pos p = new Pos(x, h, z);
          if (!terrain.type(p).equals("WHEAT")) p = new Pos(x, terrain.groundHeight(x, z) + 1, z);
          String soil = terrain.type(p.add(0, -1, 0));
          if (!Set.of("DIRT", "GRASS_BLOCK", "FARMLAND").contains(soil)
              || !hydrated(terrain, p.add(0, -1, 0))) continue;
          if (!Set.of("AIR", "CAVE_AIR", "SHORT_GRASS", "TALL_GRASS", "WHEAT")
                  .contains(terrain.type(p))
              || !terrain.clear(p.add(0, 1, 0))) continue;
          Pos stand = null;
          for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Pos q = p.add(d[0], 0, d[1]);
            if ((terrain.clear(q) || terrain.type(q).equals("WHEAT"))
                && terrain.clear(q.add(0, 1, 0))
                && (terrain.natural(q.add(0, -1, 0))
                    || terrain.type(q.add(0, -1, 0)).equals("FARMLAND"))) {
              stand = q;
              break;
            }
          }
          if (stand != null)
            result.add(new Job(Job.Kind.FARM, "farm", p, stand, "WHEAT", terrain.type(p), null));
        }
    return result;
  }

  public static boolean hydrated(Terrain t, Pos soil) {
    for (int dx = -4; dx <= 4; dx++)
      for (int dz = -4; dz <= 4; dz++)
        for (int dy = 0; dy <= 1; dy++)
          if (t.type(soil.add(dx, dy, dz)).equals("WATER")) return true;
    return false;
  }
}
