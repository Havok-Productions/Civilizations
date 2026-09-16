package dev.civilizations.world;

import dev.civilizations.core.Pos;
import dev.civilizations.navigation.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Villager;

/** Small live probes on the owning entity region; unknown regions are never read. */
final class NavigationObservation {
  private final Villager actor;

  NavigationObservation(Villager actor) {
    this.actor = actor;
  }

  private Location location(Pos p) {
    return new Location(actor.getWorld(), p.x(), p.y(), p.z());
  }

  boolean readyForPath() {
    return !actor.isSleeping()
        && (actor.isOnGround() || actor.isInWater() || actor.isInsideVehicle());
  }

  boolean surfaceWater(Pos p) {
    if (!Bukkit.isOwnedByCurrentRegion(location(p), 1)) return false;
    Block feet = location(p).getBlock(), head = feet.getRelative(0, 1, 0);
    return (feet.getType() == Material.WATER
            || feet.isPassable()
                && !feet.isLiquid()
                && feet.getRelative(0, -1, 0).getType() == Material.WATER)
        && head.isPassable()
        && !head.isLiquid();
  }

  boolean waterExit(Pos candidate, NavigationMap map) {
    Location at = actor.getLocation();
    return surfaceWater(new Pos(at.getBlockX(), at.getBlockY(), at.getBlockZ()))
        && map.surfaceWater(candidate)
        && surfaceWater(candidate);
  }

  NavigationMap.Cell cell(Pos p) {
    if (!Bukkit.isOwnedByCurrentRegion(location(p))) return null;
    Block b = location(p).getBlock();
    return new NavigationMap.Cell(
        b.getType().name(), NavigationMap.Kind.UNCLASSIFIED, b.getBlockData().getAsString());
  }

  Map<String, Object> block(Pos p, NavigationMap map) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("position", p);
    if (map != null) detail.put("snapshot", map.cell(p));
    if (!Bukkit.isOwnedByCurrentRegion(location(p))) {
      detail.put("observation", "region_not_owned");
      return detail;
    }
    Block b = location(p).getBlock();
    detail.put("material", b.getType().name());
    detail.put("block_state", b.getBlockData().getAsString());
    detail.put("passable", b.isPassable());
    detail.put("liquid", b.isLiquid());
    detail.put(
        "collision_boxes",
        b.getCollisionShape().getBoundingBoxes().stream().map(Object::toString).toList());
    return detail;
  }

  Map<String, Object> failure(Pos candidate, NavigationMap map) {
    Location at = actor.getLocation();
    Pos origin = new Pos(at.getBlockX(), at.getBlockY(), at.getBlockZ());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("candidate", candidate);
    result.put("actor_location", Map.of("x", at.getX(), "y", at.getY(), "z", at.getZ()));
    result.put(
        "actor_state",
        Map.of(
            "has_ai",
            actor.hasAI(),
            "aware",
            actor.isAware(),
            "sleeping",
            actor.isSleeping(),
            "on_ground",
            actor.isOnGround(),
            "velocity",
            actor.getVelocity().toString(),
            "bounding_box",
            actor.getBoundingBox().toString()));
    var pathfinder = actor.getPathfinder();
    result.put(
        "native_settings",
        Map.of(
            "can_open_doors",
            pathfinder.canOpenDoors(),
            "can_pass_doors",
            pathfinder.canPassDoors(),
            "can_float",
            pathfinder.canFloat(),
            "has_path",
            pathfinder.hasPath()));
    Set<Pos> positions = new LinkedHashSet<>();
    for (Pos p : List.of(origin, candidate))
      for (int dx = -1; dx <= 1; dx++)
        for (int dz = -1; dz <= 1; dz++)
          for (int dy = -1; dy <= 2; dy++) positions.add(p.add(dx, dy, dz));
    result.put("live_columns", positions.stream().map(p -> block(p, map)).toList());
    result.put(
        "engine_reason",
        "Native Pathfinder does not expose an internal rejection reason; live observations are"
            + " evidence, not an engine diagnosis");
    return result;
  }

  /** Return the exact first disallowed native node/block instead of a generic false result. */
  Map<String, Object> pathFailure(com.destroystokyo.paper.entity.Pathfinder.PathResult path) {
    Block current = actor.getLocation().getBlock();
    boolean exitingWater =
        current.getType() == Material.WATER
            || current.getRelative(0, -1, 0).getType() == Material.WATER;
    int index = 0;
    for (Location node : path.getPoints()) {
      Pos p = new Pos(node.getBlockX(), node.getBlockY(), node.getBlockZ());
      if (!Bukkit.isOwnedByCurrentRegion(node, 1))
        return Map.of("node_index", index, "node", p, "cause", "region_not_owned");
      Block feet = node.getBlock();
      for (int dy : List.of(1, 0, -1)) {
        Block b = feet.getRelative(0, dy, 0);
        String cause = "";
        if (dy == 1 && b.getType() == Material.WATER) cause = "submerged_head";
        else if (BlockObservation.dangerous(b.getType().name(), b.getBlockData().getAsString()))
          cause = "hazardous_block";
        else if (b.isLiquid() && !(exitingWater && b.getType() == Material.WATER)) cause = "liquid";
        else if (b.getBlockData() instanceof org.bukkit.block.data.Waterlogged w
            && w.isWaterlogged()) cause = "waterlogged_block";
        if (!cause.isEmpty())
          return Map.of(
              "node_index",
              index,
              "node",
              p,
              "cause",
              cause,
              "block",
              block(p.add(0, dy, 0), null));
      }
      if (feet.getType() != Material.WATER
          && feet.getRelative(0, -1, 0).getType() != Material.WATER) exitingWater = false;
      index++;
    }
    return Map.of();
  }
}
