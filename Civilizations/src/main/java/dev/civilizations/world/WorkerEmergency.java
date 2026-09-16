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
  private String cause = "";
  private long started, nextAction, nextReport;

  WorkerEmergency(CivilizationsPlugin plugin, Villager actor, Settlement village) {
    this.plugin = plugin;
    this.actor = actor;
    this.village = village;
    observation = new NavigationObservation(actor);
  }

  boolean signal(String reason) {
    if (!Set.of("SUFFOCATION", "DROWNING").contains(reason)) return false;
    cause = reason;
    return true;
  }

  boolean needed() {
    if (!cause.isEmpty()) return true;
    if (actor.isSleeping()) return false;
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
    if (started == 0) {
      started = now;
      report("emergency_started", Map.of("cause", cause, "task_retained", true));
    }
    List<Block> collisions = collisions();
    boolean wet =
        actor.isInWater() || actor.getEyeLocation().getBlock().getType() == Material.WATER;
    if (collisions.isEmpty() && !wet && actor.isOnGround()) {
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
    if (now < nextAction) return true;
    nextAction = now + 750;
    for (Block block : collisions) {
      if (!Bukkit.isOwnedByCurrentRegion(block.getLocation(), 2)) continue;
      Pos p = pos(block.getLocation());
      if (!SiteMaterials.clearable(block.getType().name()) || plugin.playerProtected(village, p))
        continue;
      Job clear =
          new Job(
              Job.Kind.CLEAR,
              "emergency-escape",
              p,
              pos(actor.getLocation()),
              "AIR",
              block.getType().name(),
              null);
      if (!plugin.mayChange(actor, block, "CLEAR_SITE")) continue;
      String problem = ClearingActions.work(plugin, actor, clear, block);
      if (problem == null) {
        report(
            "emergency_clearance",
            Map.of(
                "position",
                p,
                "before",
                clear.expected,
                "after",
                block.getType().name(),
                "inventory",
                InventoryOps.summary(actor.getInventory())));
        return true;
      }
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
    if (now >= nextReport) {
      nextReport = now + 5000;
      report(
          "emergency_wait",
          Map.of(
              "cause",
              cause,
              "colliding_blocks",
              collisions.stream()
                  .map(
                      b -> Map.of("position", pos(b.getLocation()), "material", b.getType().name()))
                  .toList(),
              "next_action",
              wet
                  ? "surface and find a dry exit"
                  : "find physical escape or removable obstruction"));
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
    plugin.debug(village.id(), actor.getUniqueId().toString(), type, data);
  }

  private static Pos pos(Location p) {
    return new Pos(p.getBlockX(), p.getBlockY(), p.getBlockZ());
  }

  private Location location(Pos p) {
    return new Location(actor.getWorld(), p.x() + .5, p.y(), p.z() + .5);
  }
}
