package dev.civilizations.world;

import dev.coreai.movement.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;

/** Pre-action collision features only. Called on the owning entity region; never loads chunks. */
final class MovementObservation {
  private MovementObservation() {}

  record Observed(MovementSample sample, Map<String, ?> context) {}

  static Observed capture(Villager actor, List<Location> path, String family) {
    // The first curriculum is for adult bodies and short transitions, not arbitrary travel.
    if (!actor.isAdult() || path.isEmpty() || path.size() > 64) return null;
    List<Location> points = new ArrayList<>();
    points.add(actor.getLocation());
    points.addAll(path);
    Map<String, String> states = new LinkedHashMap<>();
    double length = 0,
        rise = 0,
        drop = 0,
        headroom = 3,
        blocked = 0,
        support = 0,
        water = 0,
        hazard = 0;
    for (int i = 0; i < points.size(); i++) {
      Location point = points.get(i);
      if (point.getWorld() != actor.getWorld()
          || !Bukkit.isOwnedByCurrentRegion(point, 1)
          || point.getBlockY() - 1 < actor.getWorld().getMinHeight()
          || point.getY() + 3 >= actor.getWorld().getMaxHeight()) return null;
      if (i > 0) {
        Location previous = points.get(i - 1);
        double dy = point.getY() - previous.getY();
        length += Math.hypot(point.getX() - previous.getX(), point.getZ() - previous.getZ());
        rise = Math.max(rise, dy);
        drop = Math.max(drop, -dy);
      }
      double x = point.getX(), y = point.getY(), z = point.getZ();
      BoundingBox body = new BoundingBox(x - .299, y + .01, z - .299, x + .299, y + 1.94, z + .299);
      BoundingBox sole = new BoundingBox(x - .29, y - .12, z - .29, x + .29, y + .01, z + .29);
      boolean obstructed = false, supported = false, danger = false;
      for (int bx = (int) Math.floor(x - .3); bx <= Math.floor(x + .3); bx++)
        for (int bz = (int) Math.floor(z - .3); bz <= Math.floor(z + .3); bz++)
          for (int by = (int) Math.floor(y) - 1; by <= Math.floor(y + 3); by++) {
            var block = actor.getWorld().getBlockAt(bx, by, bz);
            states.put(bx + "," + by + "," + bz, block.getBlockData().getAsString());
            if (by <= y + 1
                && BlockObservation.dangerous(
                    block.getType().name(), block.getBlockData().getAsString())) danger = true;
            for (var local : block.getCollisionShape().getBoundingBoxes()) {
              var box = local.clone().shift(bx, by, bz);
              if (box.overlaps(body)) obstructed = true;
              if (box.overlaps(sole)) supported = true;
              if (box.getMaxX() > x - .299
                  && box.getMinX() < x + .299
                  && box.getMaxZ() > z - .299
                  && box.getMinZ() < z + .299
                  && box.getMaxY() > y + .01)
                headroom = Math.min(headroom, Math.max(0, box.getMinY() - y));
            }
          }
      blocked += obstructed ? 1 : 0;
      support += supported ? 1 : 0;
      hazard += danger ? 1 : 0;
      var feet = point.getBlock();
      water +=
          feet.getType() == Material.WATER
                  || feet.getBlockData() instanceof org.bukkit.block.data.Waterlogged wet
                      && wet.isWaterlogged()
              ? 1
              : 0;
    }
    double n = points.size();
    Location first = points.getFirst(), last = points.getLast();
    // Spatial groups keep repeated/revised attempts together, regardless of worker or result.
    String group =
        MovementTraining.digest(
            actor.getWorld().getUID()
                + ":"
                + family
                + ":"
                + first.getBlockX()
                + ","
                + first.getBlockY()
                + ","
                + first.getBlockZ()
                + ":"
                + last.getBlockX()
                + ","
                + last.getBlockY()
                + ","
                + last.getBlockZ());
    var sample =
        new MovementSample(
            UUID.randomUUID().toString(),
            group,
            family,
            "physical",
            List.of(
                length, rise, drop, headroom, blocked / n, support / n, water / n, 0.0, hazard / n),
            null,
            "started",
            0);
    return new Observed(
        sample,
        Map.of(
            "world",
            actor.getWorld().getUID().toString(),
            "path",
            points.stream().map(p -> List.of(p.getX(), p.getY(), p.getZ())).toList(),
            "observed_blocks",
            Map.copyOf(states),
            "sampler",
            "adult_path_points_v1"));
  }
}
