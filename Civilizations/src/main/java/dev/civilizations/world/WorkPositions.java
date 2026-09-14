package dev.civilizations.world;

import dev.civilizations.core.Pos;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Villager;

/** Re-evaluate a design's stand after construction or a proposed reach change alters access. */
public final class WorkPositions {
  private WorkPositions() {}

  public static Pos choose(Villager actor, Pos preferred, Pos target, int reach) {
    return choose(actor, preferred, target, reach, java.util.Set.of());
  }

  public static Pos choose(
      Villager actor, Pos preferred, Pos target, int reach, java.util.Set<Pos> rejected) {
    if (!Bukkit.isOwnedByCurrentRegion(at(actor, target), 1)) return preferred;
    var current = actor.getLocation();
    Pos from = new Pos(current.getBlockX(), current.getBlockY(), current.getBlockZ());
    Pos best =
        !rejected.contains(preferred) && usable(actor, preferred, target, reach) ? preferred : null;
    for (int dy : new int[] {0, -1, 1, -2, -3})
      for (int[] d :
          new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
        Pos candidate = target.add(d[0], dy, d[1]);
        if (!rejected.contains(candidate)
            && (best == null || from.distance2(candidate) < from.distance2(best))
            && usable(actor, candidate, target, reach)) best = candidate;
      }
    return best == null ? preferred : best;
  }

  private static boolean usable(Villager actor, Pos feet, Pos target, int reach) {
    if (feet.distance2(target) > reach || !Bukkit.isOwnedByCurrentRegion(at(actor, feet), 1))
      return false;
    var block = at(actor, feet).getBlock();
    if (!block.isPassable()
        || !block.getRelative(BlockFace.UP).isPassable()
        || !block.getRelative(BlockFace.DOWN).getType().isSolid()
        || !BlockRules.dry(block)) return false;
    var current = actor.getLocation();
    // Native arrival is block-granular. At a wall corner, the actual eye may still be occluded
    // even though the hypothetical center of that same cell sees the target.
    boolean occupied =
        current.getBlockX() == feet.x()
            && current.getBlockY() == feet.y()
            && current.getBlockZ() == feet.z();
    var eye = occupied ? actor.getEyeLocation() : at(actor, feet).add(0, actor.getEyeHeight(), 0);
    var delta = at(actor, target).add(0, .5, 0).toVector().subtract(eye.toVector());
    double distance = delta.length();
    if (distance > 5 || distance < .001) return false;
    var hit =
        actor
            .getWorld()
            .rayTraceBlocks(
                eye, delta.multiply(1 / distance), distance, FluidCollisionMode.NEVER, true);
    return hit == null || at(actor, target).getBlock().equals(hit.getHitBlock());
  }

  private static Location at(Villager actor, Pos p) {
    return new Location(actor.getWorld(), p.x() + .5, p.y(), p.z() + .5);
  }
}
