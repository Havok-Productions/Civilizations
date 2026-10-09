package dev.civilizations.world;

import dev.civilizations.core.Pos;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;

/** Sweeps the actual body through a cardinal surface step, including overhanging floor shapes. */
final class SurfacePassage {
  private final Villager actor;

  SurfacePassage(Villager actor) {
    this.actor = actor;
  }

  List<Block> obstructions(double x, double y, double z, Pos to) {
    Map<Pos, Block> blocked = new LinkedHashMap<>();
    double tx = to.x() + .5, tz = to.z() + .5, high = Math.max(y, to.y());
    // Rise in place, cross above the bank, then descend. Never cut diagonally through its lip.
    sample(x, y, z, x, high, z, blocked);
    sample(x, high, z, tx, high, tz, blocked);
    sample(tx, high, tz, tx, to.y(), tz, blocked);
    return List.copyOf(blocked.values());
  }

  boolean clear(Pos from, Pos to) {
    Location a = at(from), b = at(to);
    return Bukkit.isOwnedByCurrentRegion(a, 1)
        && Bukkit.isOwnedByCurrentRegion(b, 1)
        && obstructions(from.x() + .5, from.y(), from.z() + .5, to).isEmpty();
  }

  boolean fits(Pos p) {
    return clear(p, p);
  }

  String signature(Pos from, Pos to) {
    StringBuilder value = new StringBuilder();
    for (Pos p : List.of(from, to)) {
      if (!Bukkit.isOwnedByCurrentRegion(at(p), 1)) return "region_unavailable";
      for (int x = -1; x <= 1; x++)
        for (int z = -1; z <= 1; z++)
          for (int y = -1; y <= 3; y++) {
            Block b = at(p.add(x, y, z)).getBlock();
            value.append(b.getBlockData().getAsString()).append(';');
          }
    }
    return dev.coreai.agent.DiagnosticMemory.id(value.toString());
  }

  List<Map<String, Object>> observed(Pos from, Pos to) {
    Map<Pos, Map<String, Object>> cells = new LinkedHashMap<>();
    for (Pos p : List.of(from, to)) {
      if (!Bukkit.isOwnedByCurrentRegion(at(p), 1)) continue;
      for (int x = -1; x <= 1; x++)
        for (int z = -1; z <= 1; z++)
          for (int y = -1; y <= 2; y++) {
            Pos q = p.add(x, y, z);
            Block b = at(q).getBlock();
            cells.put(
                q,
                Map.of(
                    "position",
                    q,
                    "state",
                    b.getBlockData().getAsString(),
                    "collision_boxes",
                    b.getCollisionShape().getBoundingBoxes().stream()
                        .map(Object::toString)
                        .toList()));
          }
    }
    return List.copyOf(cells.values());
  }

  private void sample(
      double x, double y, double z, double tx, double ty, double tz, Map<Pos, Block> blocked) {
    int steps =
        Math.max(
            1,
            (int)
                Math.ceil(
                    Math.max(Math.abs(tx - x), Math.max(Math.abs(ty - y), Math.abs(tz - z))) / .2));
    double half = actor.getBoundingBox().getWidthX() / 2,
        height = actor.getBoundingBox().getHeight();
    for (int i = 0; i <= steps; i++) {
      double t = (double) i / steps,
          cx = x + (tx - x) * t,
          cy = y + (ty - y) * t,
          cz = z + (tz - z) * t;
      BoundingBox body =
          new BoundingBox(
              cx - half + .001,
              cy + .01,
              cz - half + .001,
              cx + half - .001,
              cy + height - .01,
              cz + half - .001);
      for (int bx = (int) Math.floor(body.getMinX()); bx <= Math.floor(body.getMaxX()); bx++)
        for (int bz = (int) Math.floor(body.getMinZ()); bz <= Math.floor(body.getMaxZ()); bz++)
          for (int by = (int) Math.floor(body.getMinY()) - 1;
              by <= Math.floor(body.getMaxY());
              by++) {
            Pos p = new Pos(bx, by, bz);
            if (!Bukkit.isOwnedByCurrentRegion(at(p), 1)) continue;
            Block b = at(p).getBlock();
            if (b.getCollisionShape().getBoundingBoxes().stream()
                .anyMatch(box -> box.clone().shift(b.getX(), b.getY(), b.getZ()).overlaps(body)))
              blocked.put(p, b);
          }
    }
  }

  private Location at(Pos p) {
    return new Location(actor.getWorld(), p.x(), p.y(), p.z());
  }
}
