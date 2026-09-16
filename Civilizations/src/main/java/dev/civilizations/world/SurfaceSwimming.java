package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.Pos;
import dev.civilizations.navigation.SurfaceEscape;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Villager;

/** Own-region physical steering along a freshly observed surface route; no teleportation. */
final class SurfaceSwimming {
  private final Villager actor;
  private final CivilizationsPlugin plugin;
  private List<Pos> route = List.of();
  private long planned;
  private Map<String, Object> evidence = Map.of();

  SurfaceSwimming(CivilizationsPlugin plugin, Villager actor) {
    this.plugin = plugin;
    this.actor = actor;
  }

  Map<String, Object> evidence() {
    return evidence;
  }

  boolean active() {
    return !route.isEmpty();
  }

  boolean tick(long now, Pos goal) {
    Location at = actor.getLocation();
    Pos feet = new Pos(at.getBlockX(), at.getBlockY(), at.getBlockZ());
    Pos start = null;
    for (int dy : new int[] {0, 1, -1})
      if (cell(feet.add(0, dy, 0)) == SurfaceEscape.Cell.WATER) {
        start = feet.add(0, dy, 0);
        break;
      }
    if (start == null) {
      evidence = Map.of("reason", "no_breathable_surface_start", "position", feet);
      return false;
    }
    while (!route.isEmpty()) {
      Pos next = route.getFirst();
      double dx = next.x() + .5 - at.getX(), dz = next.z() + .5 - at.getZ();
      if (dx * dx + dz * dz > .16 || at.getY() < next.y() - .15) break;
      route = route.subList(1, route.size());
    }
    if (route.isEmpty()
        && now - planned < 2500
        && !"observed_surface_exit".equals(evidence.get("reason"))) return false;
    if (route.isEmpty()
        || now - planned > 2500
        || cell(route.getFirst()) == SurfaceEscape.Cell.BLOCKED) {
      int radius = plugin.navigation().radiusFor(actor.getUniqueId().toString());
      var found = SurfaceEscape.find(start, goal, radius, this::cell);
      route = found.steps();
      planned = now;
      evidence =
          Map.of(
              "reason",
              found.reason(),
              "start",
              start,
              "route",
              route,
              "observed_cells",
              found.observed(),
              "radius",
              radius);
    }
    if (route.isEmpty()) return false;
    Pos next = route.getFirst();
    if (Math.abs(next.x() - feet.x()) > 1 || Math.abs(next.z() - feet.z()) > 1) {
      route = List.of();
      return false;
    }
    // Stop native navigation because it may route below this breathable surface.
    actor.getPathfinder().stopPathfinding();
    double dx = next.x() + .5 - at.getX(),
        dz = next.z() + .5 - at.getZ(),
        distance = Math.hypot(dx, dz);
    var velocity = actor.getVelocity();
    if (distance > .05) {
      double speed = Math.min(.14, distance * .25);
      velocity.setX(dx / distance * speed).setZ(dz / distance * speed);
      actor.setRotation((float) Math.toDegrees(Math.atan2(-dx, dz)), 0);
    }
    if (next.y() > at.getY() + .1 || actor.getEyeLocation().getBlock().getType() == Material.WATER)
      velocity.setY(Math.max(velocity.getY(), .20));
    actor.setVelocity(velocity);
    return true;
  }

  private SurfaceEscape.Cell cell(Pos p) {
    Location at = new Location(actor.getWorld(), p.x(), p.y(), p.z());
    if (!Bukkit.isOwnedByCurrentRegion(at, 1)) return SurfaceEscape.Cell.BLOCKED;
    Block feet = at.getBlock(),
        head = feet.getRelative(0, 1, 0),
        floor = feet.getRelative(0, -1, 0);
    if (!open(head)) return SurfaceEscape.Cell.BLOCKED;
    if (!danger(floor)
        && (feet.getType() == Material.WATER || open(feet) && floor.getType() == Material.WATER))
      return SurfaceEscape.Cell.WATER;
    return open(feet) && floor.getType().isSolid() && !danger(floor)
        ? SurfaceEscape.Cell.LAND
        : SurfaceEscape.Cell.BLOCKED;
  }

  private boolean open(Block b) {
    return b.isPassable() && !b.isLiquid() && !danger(b);
  }

  private boolean danger(Block b) {
    return BlockObservation.dangerous(b.getType().name(), b.getBlockData().getAsString())
        || b.getBlockData() instanceof org.bukkit.block.data.Waterlogged w && w.isWaterlogged();
  }
}
