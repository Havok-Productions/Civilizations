package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;

/** Immediate physical escape, independent of model availability and the ordinary task queue. */
final class WorkerEmergency {
  private final CivilizationsPlugin plugin;
  private final Villager actor;
  private final Settlement village;
  private final NavigationObservation observation;
  private final SurfaceSwimming swimming;
  private final EmergencyClearance clearance;
  private final Set<String> recordedWaits = new HashSet<>();
  private final Set<String> persistedWaits = new HashSet<>();
  private org.bukkit.Location waterPosition;
  private long waterProgress;
  private String cause = "";
  private Pos resumeGoal;
  private long started, nextAction, nextReport;

  WorkerEmergency(CivilizationsPlugin plugin, Villager actor, Settlement village) {
    this.plugin = plugin;
    this.actor = actor;
    this.village = village;
    observation = new NavigationObservation(actor);
    swimming = new SurfaceSwimming(plugin, actor);
    clearance = new EmergencyClearance(plugin, actor, village);
  }

  boolean signal(String reason) {
    if (!Set.of("SUFFOCATION", "DROWNING").contains(reason)) return false;
    cause = reason;
    return true;
  }

  boolean needed() {
    if (!cause.isEmpty()) return true;
    if (actor.isSleeping()) return false;
    long now = System.currentTimeMillis();
    var at = actor.getLocation();
    if (actor.isInWater() || observation.surfaceWater(pos(at))) {
      double dx = waterPosition == null ? 0 : at.getX() - waterPosition.getX();
      double dz = waterPosition == null ? 0 : at.getZ() - waterPosition.getZ();
      if (waterPosition == null || dx * dx + dz * dz > .25) {
        waterPosition = at.clone();
        waterProgress = now;
      } else if (now - waterProgress >= 3000) {
        cause = "STRANDED_WATER";
        return true;
      }
    } else waterPosition = null;
    Block eye = actor.getEyeLocation().getBlock();
    if (eye.getType() == Material.WATER && actor.getRemainingAir() < actor.getMaximumAir() / 2)
      return signal("DROWNING");
    if (Bukkit.isOwnedByCurrentRegion(eye.getLocation(), 1)
        && eye.getCollisionShape().getBoundingBoxes().stream()
            .anyMatch(
                b ->
                    b.clone()
                        .shift(eye.getX(), eye.getY(), eye.getZ())
                        .contains(actor.getEyeLocation().toVector()))) return signal("SUFFOCATION");
    return false;
  }

  boolean tick(long now, Pos resumeTarget) {
    resumeGoal = resumeTarget;
    if (started == 0) {
      started = now;
      report("emergency_started", Map.of("cause", cause, "task_retained", true));
    }
    List<Block> collisions = collisions();
    boolean wet =
        actor.isInWater() || actor.getEyeLocation().getBlock().getType() == Material.WATER;
    if (collisions.isEmpty() && !wet && actor.isOnGround()) {
      swimming.escaped(now);
      actor.getPathfinder().stopPathfinding();
      report(
          "emergency_escaped",
          Map.of(
              "cause",
              cause,
              "elapsed_ms",
              now - started,
              "inventory",
              InventoryOps.summary(actor.getInventory()),
              "task_retained",
              true));
      cause = "";
      waterPosition = null;
      swimming.reset();
      recordedWaits.clear();
      persistedWaits.clear();
      started = nextAction = 0;
      return false;
    }
    // Drowning must not wait for a language model or an empty search slot. Normal swimming
    // buoyancy only; collision physics still applies and no block or entity is teleported.
    Block eye = actor.getEyeLocation().getBlock();
    if (eye.getType() == Material.WATER && Bukkit.isOwnedByCurrentRegion(eye.getLocation(), 1)) {
      Block above = eye.getRelative(0, 1, 0);
      if (above.isPassable() && (!above.isLiquid() || above.getType() == Material.WATER)) {
        var velocity = actor.getVelocity();
        if (velocity.getY() < .16) actor.setVelocity(velocity.setY(.16));
      }
    }
    boolean steering = (wet || swimming.active()) && swimming.tick(now, resumeTarget);
    var failure = swimming.takeFailure();
    if (!failure.isEmpty()) report("emergency_route_failed", failure);
    if (steering) {
      if (now >= nextReport) {
        nextReport = now + 5000;
        report("emergency_surface_route", swimming.evidence());
      }
      return true;
    }
    if (now < nextAction) return true;
    nextAction = now + 750;
    List<Block> obstacles = new ArrayList<>(collisions);
    obstacles.addAll(swimming.blockers());
    var cleared = clearance.tick(obstacles, now);
    if (!cleared.isEmpty()) {
      if (!cleared.containsKey("pending_pose")) report("emergency_clearance", cleared);
      return true;
    }
    // Report before the native fallback: accepting a native path is not proof of movement either.
    if (now >= nextReport) {
      nextReport = now + 5000;
      Pos feet = pos(actor.getLocation());
      var passage = new SurfacePassage(actor);
      var detail = new LinkedHashMap<String, Object>();
      detail.put("cause", cause);
      detail.put("reason", "no_executed_surface_exit");
      detail.put("surface_escape", swimming.evidence());
      detail.put("terrain_signature", passage.signature(feet, feet));
      detail.put("observed_terrain", passage.observed(feet, feet));
      detail.put("clearance_refusals", clearance.refusals());
      detail.put(
          "next_action", "reobserve changed terrain, alternate exit or feasible natural clearance");
      report("emergency_wait", detail);
    }
    if (observation.readyForPath()) {
      List<Pos> candidates = new ArrayList<>();
      Pos at = pos(actor.getLocation());
      for (int dy = -2; dy <= 2; dy++)
        for (int x = -8; x <= 8; x++)
          for (int z = -8; z <= 8; z++) {
            Pos p = at.add(x, dy, z);
            Location target = location(p);
            if (!Bukkit.isOwnedByCurrentRegion(target, 1)) continue;
            Block feet = target.getBlock(),
                head = feet.getRelative(0, 1, 0),
                floor = feet.getRelative(0, -1, 0);
            if (feet.isPassable()
                && !feet.isLiquid()
                && head.isPassable()
                && !head.isLiquid()
                && floor.getType().isSolid()
                && !BlockObservation.dangerous(
                    floor.getType().name(), floor.getBlockData().getAsString())) candidates.add(p);
          }
      candidates.sort(
          Comparator.<Pos>comparingLong(at::distance2)
              .thenComparingLong(p -> resumeTarget == null ? 0 : p.distance2(resumeTarget)));
      for (Pos p : candidates) {
        var path = actor.getPathfinder().findPath(location(p));
        if (path == null
            || path.getFinalPoint() == null
            || pos(path.getFinalPoint()).distance2(p) > 1
            || !observation.pathFailure(path).isEmpty()) continue;
        if (actor.getPathfinder().moveTo(path, plugin.speed())) return true;
      }
    }
    return true;
  }

  private List<Block> collisions() {
    BoundingBox body = actor.getBoundingBox().clone().expand(-.01);
    List<Block> blocks = new ArrayList<>();
    for (int y = (int) Math.floor(body.getMaxY()); y >= (int) Math.floor(body.getMinY()); y--)
      for (int x = (int) Math.floor(body.getMinX()); x <= (int) Math.floor(body.getMaxX()); x++)
        for (int z = (int) Math.floor(body.getMinZ()); z <= (int) Math.floor(body.getMaxZ()); z++) {
          Location p = new Location(actor.getWorld(), x, y, z);
          if (!Bukkit.isOwnedByCurrentRegion(p, 1)) continue;
          Block block = p.getBlock();
          if (block.getCollisionShape().getBoundingBoxes().stream()
              .anyMatch(b -> b.clone().shift(p.toVector()).overlaps(body))) blocks.add(block);
        }
    return blocks;
  }

  private void report(String type, Map<String, ?> data) {
    var detail = new LinkedHashMap<String, Object>(data);
    var at = actor.getLocation();
    detail.put("actor_location", Map.of("x", at.getX(), "y", at.getY(), "z", at.getZ()));
    detail.put("velocity", actor.getVelocity().toString());
    detail.put("cause", cause);
    if (resumeGoal != null) detail.put("original_goal", resumeGoal);
    detail.put("elapsed_ms", System.currentTimeMillis() - started);
    String state = Objects.toString(detail.get("terrain_signature"), "") + pos(at).key();
    boolean changed = !type.equals("emergency_wait") || recordedWaits.add(state);
    boolean persist =
        !type.equals("emergency_wait")
            || System.currentTimeMillis() - started
                    >= WorkerTuning.value(plugin, actor, "navigation.transition_ms")
                && persistedWaits.add(state);
    if (plugin.experiments() != null && !type.equals("emergency_surface_route") && persist)
      plugin.experiments().emergency(village.id(), actor.getUniqueId().toString(), type, detail);
    if (!changed) {
      detail.remove("observed_terrain");
      detail.remove("clearance_refusals");
      detail.put("unchanged_observation", true);
    }
    plugin.debug(village.id(), actor.getUniqueId().toString(), type, detail);
  }

  private static Pos pos(Location p) {
    return new Pos(p.getBlockX(), p.getBlockY(), p.getBlockZ());
  }

  private Location location(Pos p) {
    return new Location(actor.getWorld(), p.x() + .5, p.y(), p.z() + .5);
  }
}
