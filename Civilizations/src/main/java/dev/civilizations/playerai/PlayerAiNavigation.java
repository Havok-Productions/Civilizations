package dev.civilizations.playerai;

import dev.civilizations.core.Pos;
import dev.civilizations.navigation.*;
import dev.civilizations.world.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Mannequin;
import org.bukkit.util.Vector;

/** Bounded walking adapter; snapshots/search are async, steering is entity-region owned. */
final class PlayerAiNavigation {
  private final Mannequin body;
  private final RegionSnapshots snapshots;
  private final Executor executor;
  private final int radius;
  private CompletableFuture<TerrainRouteSearch.Result> pending;
  private List<TerrainRouteSearch.Step> route = List.of();
  private int index;
  private Pos target;
  private int targetReach;
  private long deadline;

  PlayerAiNavigation(Mannequin body, RegionSnapshots snapshots, Executor executor, int radius) {
    this.body = body;
    this.snapshots = snapshots;
    this.executor = executor;
    this.radius = radius;
  }

  static Pos pos(Location at) {
    return new Pos(at.getBlockX(), at.getBlockY(), at.getBlockZ());
  }

  PlayerAi.Outcome approach(Pos goal) {
    return approach(goal, 6);
  }

  PlayerAi.Outcome approach(Pos goal, int reach) {
    Pos here = pos(body.getLocation());
    if (reach == 0 && here.equals(goal)) {
      Location at = body.getLocation();
      double dx = goal.x() + .5 - at.getX(), dz = goal.z() + .5 - at.getZ();
      double distance = Math.sqrt(dx * dx + dz * dz);
      if (distance > .15) {
        if (!safe(goal)) {
          halt();
          return PlayerAi.Outcome.blocked("Standing position became unsafe");
        }
        body.setRotation((float) Math.toDegrees(Math.atan2(-dx, dz)), 0);
        double speed = Math.min(.12, distance / 2);
        body.setVelocity(
            new Vector(dx / distance * speed, body.getVelocity().getY(), dz / distance * speed));
        return PlayerAi.Outcome.waiting("Centering on the work position");
      }
    }
    if (here.distance2(goal) <= reach) {
      halt();
      return PlayerAi.Outcome.done(null, null);
    }
    long now = System.currentTimeMillis();
    if (!goal.equals(target) || reach != targetReach) {
      halt();
      target = goal;
      targetReach = reach;
      deadline = now + 20_000;
      pending =
          snapshots
              .capture(body.getWorld(), here, radius)
              .thenApplyAsync(
                  terrain -> {
                    var map = NavigationTerrain.capture(terrain, here, radius, Set.of());
                    return TerrainRouteSearch.search(map, here, goal, reach, Set.of(), 0, 12_000);
                  },
                  executor);
    }
    if (now > deadline) {
      halt();
      return PlayerAi.Outcome.blocked("Walking timed out; no teleport or excavation attempted");
    }
    if (pending != null) {
      if (!pending.isDone()) return PlayerAi.Outcome.waiting("Planning a loaded-terrain route");
      try {
        var result = pending.join();
        pending = null;
        if (!result.reached()
            || result.steps().stream().anyMatch(s -> !s.open().isEmpty() || !s.clear().isEmpty())) {
          halt();
          return PlayerAi.Outcome.blocked(
              "No simple walking route; doors, digging and swimming are future skills");
        }
        route = result.steps();
      } catch (CompletionException | CancellationException error) {
        halt();
        return PlayerAi.Outcome.blocked("Terrain route unavailable");
      }
    }
    Location at = body.getLocation();
    while (index < route.size()) {
      Pos step = route.get(index).feet();
      double dx = step.x() + .5 - at.getX(), dz = step.z() + .5 - at.getZ();
      if (dx * dx + dz * dz < .12 && Math.abs(step.y() - at.getY()) < .6) {
        index++;
        continue;
      }
      if (!safe(step)) {
        halt();
        return PlayerAi.Outcome.blocked("Walking route changed or crosses an unowned region");
      }
      double length = Math.sqrt(dx * dx + dz * dz);
      if (length < .01) return PlayerAi.Outcome.waiting("Waiting to land");
      double y = body.getVelocity().getY();
      if (step.y() > at.getY() + .4 && body.isOnGround()) y = .42;
      body.setRotation((float) Math.toDegrees(Math.atan2(-dx, dz)), 0);
      body.setVelocity(new Vector(dx / length * .17, y, dz / length * .17));
      return PlayerAi.Outcome.waiting("Walking to " + goal.key());
    }
    halt();
    return PlayerAi.Outcome.blocked("Route ended outside interaction reach");
  }

  private boolean safe(Pos p) {
    World world = body.getWorld();
    // Collision checks may inspect neighbor blocks, so own all surrounding chunks first.
    if (!ownedLoaded(world, p, 1)) return false;
    if (p.y() <= world.getMinHeight() || p.y() + 2 >= world.getMaxHeight()) return false;
    var feet = world.getBlockAt(p.x(), p.y(), p.z());
    var head = feet.getRelative(0, 1, 0);
    var floor = feet.getRelative(0, -1, 0);
    return world.getWorldBorder().isInside(feet.getLocation())
        && feet.isPassable()
        && head.isPassable()
        && !feet.isLiquid()
        && !head.isLiquid()
        && floor.getType().isSolid()
        && floor.getType().isOccluding()
        && !Set.of(Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE)
            .contains(floor.getType())
        && !Set.of(
                Material.FIRE, Material.SOUL_FIRE, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH)
            .contains(feet.getType());
  }

  static boolean ownedLoaded(World world, Pos p, int blockRadius) {
    for (int x = (p.x() - blockRadius) >> 4; x <= (p.x() + blockRadius) >> 4; x++)
      for (int z = (p.z() - blockRadius) >> 4; z <= (p.z() + blockRadius) >> 4; z++)
        if (!world.isChunkLoaded(x, z) || !Bukkit.isOwnedByCurrentRegion(world, x, z)) return false;
    return true;
  }

  void halt() {
    if (pending != null) pending.cancel(false);
    pending = null;
    route = List.of();
    index = 0;
    target = null;
    if (body.isValid()) body.setVelocity(new Vector(0, body.getVelocity().getY(), 0));
  }
}
