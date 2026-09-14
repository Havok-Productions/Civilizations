package dev.civilizations.world;

import dev.civilizations.core.Pos;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Villager;

/** Small live scan around a worker; no inference, no chunk loads, no cross-region reads. */
final class NearbyResources {
  static List<Pos> find(Villager actor, Pos at, String material) {
    List<Pos> result = new ArrayList<>();
    World world = actor.getWorld();
    for (int r = 0; r <= 12; r++)
      for (int dx = -r; dx <= r; dx++)
        for (int dz = -r; dz <= r; dz++) {
          if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
          if (!Bukkit.isOwnedByCurrentRegion(
              new Location(world, at.x() + dx, at.y(), at.z() + dz), 1)) continue;
          for (int dy = -4; dy <= 5; dy++) {
            Pos p = at.add(dx, dy, dz);
            Block b = world.getBlockAt(p.x(), p.y(), p.z());
            String type = b.getType().name();
            boolean match = dev.civilizations.core.MaterialSources.matches(material, type);
            if (!match) continue;
            if (type.endsWith("_LOG")) {
              if (!Set.of("DIRT", "GRASS_BLOCK", "PODZOL", "ROOTED_DIRT")
                      .contains(b.getRelative(0, -1, 0).getType().name())
                  || !BlockRules.tree(b)) continue;
            } else if (!material.equals("WHEAT_SEEDS")) {
              boolean exposed =
                  b.getRelative(0, 1, 0).isPassable() && b.getRelative(0, 2, 0).isPassable();
              for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                if (b.getRelative(d[0], 0, d[1]).isPassable()
                    && b.getRelative(d[0], 1, d[1]).isPassable()) exposed = true;
              if (!exposed) continue;
            }
            result.add(p);
          }
        }
    return result;
  }
}
