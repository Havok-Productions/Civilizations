package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.Pos;
import dev.civilizations.navigation.SurfaceEscape;
import dev.civilizations.navigation.SurfaceProgress;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Villager;

/** Own-region physical steering along a freshly observed surface route; no teleportation. */
final class SurfaceSwimming {
  private final Villager actor;
  private final CivilizationsPlugin plugin;
  private final SurfacePassage passage;
  private final SurfaceProgress progress = new SurfaceProgress();
  private List<Pos> route = List.of();
  private List<Block> blockers = List.of();
  private Map<String, Object> failure = Map.of();
  private Pos routeStart;
  private long planned;
  private Map<String, Object> evidence = Map.of();
  private dev.coreai.movement.MovementLearning.Attempt movementAttempt;
  private Pos sampledTarget;

  private void finishMovement(Boolean success, String reason, long now) {
    if (movementAttempt != null && plugin.coreAi() != null)
      plugin.coreAi().movement().finish(movementAttempt, success, reason, now);
    movementAttempt = null;
    sampledTarget = null;
  }

  SurfaceSwimming(CivilizationsPlugin plugin, Villager actor) {
    this.plugin = plugin;
    this.actor = actor;
    passage = new SurfacePassage(actor);
  }

  Map<String, Object> takeFailure() {
    var result = failure;
    failure = Map.of();
    return result;
  }

  List<Block> blockers() {
    return blockers;
  }

  void reset() {
    finishMovement(null, "surface_controller_reset", System.currentTimeMillis());
    route = List.of();
    blockers = List.of();
    progress.reset();
    planned = 0;
  }

  void escaped(long now) {
    if (sampledTarget != null) {
      var at = actor.getLocation();
      boolean arrived =
          Math.hypot(at.getX() - sampledTarget.x() - .5, at.getZ() - sampledTarget.z() - .5) <= .8
              && Math.abs(at.getY() - sampledTarget.y()) <= .5;
      finishMovement(arrived ? Boolean.TRUE : null, "verified_dry_escape", now);
    }
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
      finishMovement(null, "surface_start_unavailable", now);
      evidence = Map.of("reason", "no_breathable_surface_start", "position", feet);
      return false;
    }
    while (!route.isEmpty()) {
      Pos next = route.getFirst();
      double dx = next.x() + .5 - at.getX(), dz = next.z() + .5 - at.getZ();
      if (dx * dx + dz * dz > .0225 || at.getY() < next.y() - .15) break;
      progress.reached(next);
      if (next.equals(sampledTarget)) finishMovement(true, "surface_waypoint_arrived", now);
      routeStart = next;
      route = route.subList(1, route.size());
    }
    if (route.isEmpty()
        && now - planned < 2500
        && !"observed_surface_exit".equals(evidence.get("reason"))) return false;
    if (route.isEmpty()
        || now - planned > 2500
        || cell(route.getFirst()) == SurfaceEscape.Cell.BLOCKED) {
      int radius = plugin.navigation().radiusFor(actor.getUniqueId().toString());
      var found =
          SurfaceEscape.find(
              start,
              goal,
              radius,
              this::cell,
              (from, to) -> {
                var edge = new SurfaceProgress.Edge(from, to);
                return !(progress.remembered(edge)
                        && progress.blocked(edge, passage.signature(from, to)))
                    && passage.clear(from, to);
              });
      route = found.steps();
      routeStart = start;
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
    if (route.isEmpty()) {
      finishMovement(null, "surface_route_unavailable", now);
      return false;
    }
    Pos next = route.getFirst();
    if (sampledTarget != null && !next.equals(sampledTarget))
      finishMovement(null, "surface_waypoint_replaced", now);
    if (Math.abs(next.x() - feet.x()) > 1 || Math.abs(next.z() - feet.z()) > 1) {
      finishMovement(null, "surface_route_invalidated", now);
      route = List.of();
      return false;
    }
    var edge = new SurfaceProgress.Edge(routeStart, next);
    blockers = passage.obstructions(at.getX(), at.getY(), at.getZ(), next);
    double remaining = Math.hypot(next.x() + .5 - at.getX(), next.z() + .5 - at.getZ());
    boolean stalled =
        progress.stalled(
            edge, remaining, now, WorkerTuning.value(plugin, actor, "navigation.transition_ms"));
    if (stalled || !blockers.isEmpty()) {
      finishMovement(
          false, stalled ? "surface_no_progress" : "surface_collision_after_steering", now);
      String signature = passage.signature(edge.from(), edge.to());
      progress.reject(edge, signature);
      failure =
          Map.of(
              "reason",
              stalled ? "surface_escape_no_progress" : "surface_escape_collision",
              "transition",
              edge,
              "terrain_signature",
              signature,
              "observed_terrain",
              passage.observed(edge.from(), edge.to()),
              "blocking_blocks",
              blockers.stream()
                  .map(
                      b ->
                          Map.of(
                              "position",
                              new Pos(b.getX(), b.getY(), b.getZ()),
                              "state",
                              b.getBlockData().getAsString()))
                  .toList(),
              "remaining_distance",
              remaining,
              "route",
              List.copyOf(route));
      evidence = failure;
      route = List.of();
      planned = 0;
      actor.getPathfinder().stopPathfinding();
      var stopped = actor.getVelocity();
      actor.setVelocity(stopped.setX(0).setZ(0));
      return false;
    }
    // Stop native navigation because it may route below this breathable surface.
    actor.getPathfinder().stopPathfinding();
    double dx = next.x() + .5 - at.getX(),
        dz = next.z() + .5 - at.getZ(),
        distance = Math.hypot(dx, dz);
    // Center the perpendicular axis before a turn so a valid cardinal route does not cut a corner.
    if (next.x() != routeStart.x() && Math.abs(routeStart.z() + .5 - at.getZ()) > .12) {
      dx = 0;
      dz = routeStart.z() + .5 - at.getZ();
    } else if (next.z() != routeStart.z() && Math.abs(routeStart.x() + .5 - at.getX()) > .12) {
      dz = 0;
      dx = routeStart.x() + .5 - at.getX();
    }
    distance = Math.hypot(dx, dz);
    var velocity = actor.getVelocity();
    if (distance > .05) {
      double speed = Math.min(.14, distance * .25);
      velocity.setX(dx / distance * speed).setZ(dz / distance * speed);
      actor.setRotation((float) Math.toDegrees(Math.atan2(-dx, dz)), 0);
    }
    if (next.y() > at.getY() + .1 || actor.getEyeLocation().getBlock().getType() == Material.WATER)
      velocity.setY(Math.max(velocity.getY(), .20));
    var sample =
        movementAttempt == null && plugin.coreAi() != null
            ? MovementObservation.capture(
                actor,
                List.of(new Location(actor.getWorld(), next.x() + .5, next.y(), next.z() + .5)),
                "surface_swim")
            : null;
    actor.setVelocity(velocity);
    if (sample != null) {
      sampledTarget = next;
      movementAttempt =
          plugin
              .coreAi()
              .movement()
              .begin(sample.sample(), actor.getUniqueId().toString(), now, sample.context());
    }
    return true;
  }

  private SurfaceEscape.Cell cell(Pos p) {
    Location at = new Location(actor.getWorld(), p.x(), p.y(), p.z());
    if (!Bukkit.isOwnedByCurrentRegion(at, 1)) return SurfaceEscape.Cell.BLOCKED;
    Block feet = at.getBlock(),
        head = feet.getRelative(0, 1, 0),
        floor = feet.getRelative(0, -1, 0);
    if (!open(head) || !passage.fits(p)) return SurfaceEscape.Cell.BLOCKED;
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
