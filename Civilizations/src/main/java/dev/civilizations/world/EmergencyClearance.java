package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Villager;

/** Visible, paid-in-drops clearance of the immediate natural shore/escape obstruction. */
final class EmergencyClearance {
  private final CivilizationsPlugin plugin;
  private final Villager actor;
  private final Settlement village;
  private final WorkPose pose;
  private final SurfacePassage passage;
  private final Map<Pos, Map.Entry<String, String>> unavailable = new HashMap<>();
  private List<Map<String, Object>> refusals = List.of();

  EmergencyClearance(CivilizationsPlugin plugin, Villager actor, Settlement village) {
    this.plugin = plugin;
    this.actor = actor;
    this.village = village;
    pose = new WorkPose(plugin, actor);
    passage = new SurfacePassage(actor);
  }

  List<Map<String, Object>> refusals() {
    return refusals;
  }

  Map<String, Object> tick(List<Block> observedBlockers, long now) {
    Map<Pos, Block> candidates = new LinkedHashMap<>();
    observedBlockers.forEach(b -> candidates.put(pos(b), b));
    var at = actor.getLocation();
    // Only immediate cardinal shore columns, top first; not arbitrary nearby village structures.
    for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
      for (int dy = 2; dy >= 0; dy--) {
        Location position =
            new Location(
                actor.getWorld(),
                at.getBlockX() + d[0],
                at.getBlockY() + dy,
                at.getBlockZ() + d[1]);
        if (!Bukkit.isOwnedByCurrentRegion(position, 1)) continue;
        Block b = position.getBlock();
        candidates.put(pos(b), b);
      }
    List<Map<String, Object>> refused = new ArrayList<>();
    for (Block block :
        candidates.values().stream()
            .sorted(Comparator.comparingInt(Block::getY).reversed())
            .toList()) {
      if (!Bukkit.isOwnedByCurrentRegion(block.getLocation(), 1) || block.isPassable()) continue;
      Pos p = pos(block);
      if (p.y() < at.getBlockY()) continue; // never remove the actor's support
      String type = block.getType().name();
      String state = passage.signature(p, p);
      var old = unavailable.get(p);
      if (old != null && old.getKey().equals(state)) {
        refused.add(
            Map.of(
                "position",
                p,
                "state",
                block.getBlockData().getAsString(),
                "reason",
                old.getValue()));
        continue;
      }
      unavailable.remove(p);
      boolean tree = (type.endsWith("_LOG") || type.endsWith("_LEAVES")) && BlockRules.tree(block);
      String reason = "";
      if (plugin.playerProtected(village, p) || village.layoutOccupancy().contains(p))
        reason = "protected_or_structure";
      else if (!SiteMaterials.clearable(type) && !tree) reason = "not_verified_natural_clearance";
      else if (!pose.accessible(block)) reason = "not_visible_within_reach";
      else if (!plugin.mayChange(actor, block, "CLEAR_SITE")) reason = "change_denied";
      if (!reason.isEmpty()) {
        refused.add(
            Map.of("position", p, "state", block.getBlockData().getAsString(), "reason", reason));
        continue;
      }
      if (!pose.ready(block, now)) {
        refusals = List.copyOf(refused);
        return Map.of("pending_pose", p);
      }
      Job clear =
          new Job(
              Job.Kind.CLEAR,
              "emergency-escape",
              p,
              new Pos(at.getBlockX(), at.getBlockY(), at.getBlockZ()),
              "AIR",
              type,
              tree ? "natural-tree" : null);
      String problem = ClearingActions.escape(plugin, actor, clear, block);
      if (problem == null) {
        refusals = List.copyOf(refused);
        pose.reset();
        return Map.of(
            "position",
            p,
            "before",
            type,
            "after",
            block.getType().name(),
            "inventory",
            InventoryOps.summary(actor.getInventory()));
      }
      refused.add(
          Map.of("position", p, "state", block.getBlockData().getAsString(), "reason", problem));
      // Preconditions are rechecked after surrounding blocks OR inventory changes.
      if (!problem.startsWith("No room")) unavailable.put(p, Map.entry(state, problem));
    }
    refusals = List.copyOf(refused);
    return Map.of();
  }

  private static Pos pos(Block b) {
    return new Pos(b.getX(), b.getY(), b.getZ());
  }
}
