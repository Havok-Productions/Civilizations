package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import dev.civilizations.navigation.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;

/** One verified, paid-for obstacle action per work interval. Never excavates arbitrary terrain. */
public final class RouteClearance {
  public record Result(
      boolean ready, boolean changed, String failure, Map<String, Object> details) {
    public Result(boolean ready, boolean changed, String failure) {
      this(ready, changed, failure, Map.of());
    }
  }

  private final CivilizationsPlugin plugin;
  private final Villager actor;
  private final Settlement village;
  private long next;

  public RouteClearance(CivilizationsPlugin plugin, Villager actor, Settlement village) {
    this.plugin = plugin;
    this.actor = actor;
    this.village = village;
  }

  private Location location(Pos p) {
    return new Location(actor.getWorld(), p.x() + .5, p.y(), p.z() + .5);
  }

  public Result prepare(TerrainRouteSearch.Step step, NavigationMap map, long now) {
    return prepare(step, map, now, null);
  }

  public Result prepare(TerrainRouteSearch.Step step, NavigationMap map, long now, Pos buildSite) {
    boolean opened = false;
    for (Pos p : step.open()) {
      if (!Bukkit.isOwnedByCurrentRegion(location(p), 2))
        return waiting("door_region_not_owned", p, map);
      Block b = location(p).getBlock();
      if (!(b.getBlockData() instanceof Openable open))
        return blocked("door_or_gate_changed", p, map);
      if (open.isOpen()) continue;
      if (actor.getLocation().distanceSquared(location(p)) > 16)
        return blocked("door_out_of_reach", p, map);
      if (!plugin.mayChange(actor, b, "OPEN_GATE"))
        return blocked("door_opening_protected", p, map);
      Block other = null;
      Openable second = null;
      if (open instanceof Door door) {
        other =
            b.getRelative(
                door.getHalf() == org.bukkit.block.data.Bisected.Half.BOTTOM
                    ? BlockFace.UP
                    : BlockFace.DOWN);
        if (other.getBlockData() instanceof Openable value) {
          second = value;
          if (!plugin.mayChange(actor, other, "OPEN_GATE"))
            return blocked("door_other_half_protected", p, map);
        }
      }
      String before = b.getBlockData().getAsString();
      open.setOpen(true);
      b.setBlockData(open, false);
      if (second != null) {
        second.setOpen(true);
        other.setBlockData(second, false);
      }
      opened = true;
      plugin.debug(
          village.id(),
          actor.getUniqueId().toString(),
          "navigation_obstacle",
          Map.of(
              "position",
              p,
              "action",
              "open",
              "before",
              before,
              "after",
              b.getBlockData().getAsString(),
              "verified",
              ((Openable) b.getBlockData()).isOpen()));
    }
    List<Pos> clear =
        step.clear().stream().sorted(java.util.Comparator.comparingInt(Pos::y).reversed()).toList();
    for (Pos p : clear) {
      if (!Bukkit.isOwnedByCurrentRegion(location(p), 3))
        return waiting("clearance_region_not_owned", p, map);
      Block b = location(p).getBlock();
      if (b.getType().isAir()) continue;
      if (now < next)
        return new Result(
            false,
            false,
            "",
            Map.of("wait_reason", "work_interval", "position", p, "retry_after_ms", next - now));
      if (actor.getLocation().distanceSquared(location(p)) > 16)
        return blocked("natural_obstacle_out_of_reach", p, map);
      if (!b.getType().name().equals(map.cell(p).material()))
        return blocked("obstacle_material_changed", p, map);
      boolean learnedClutter =
          plugin.experiments() != null
              && BlockObservation.learnedClear(
                  plugin.experiments().rules(), actor.getUniqueId().toString(), b);
      boolean ownSite =
          learnedClutter
              && p.equals(buildSite)
              && village.ownsBuildSite(p, actor.getUniqueId().toString(), now);
      if (village.gatherProtected(p) && !ownSite || plugin.playerProtected(village, p))
        return blocked("obstacle_reserved_or_player_protected", p, map);
      Terrain live =
          new Terrain() {
            public int height(int x, int z) {
              return 0;
            }

            public boolean available(int x, int z) {
              return Bukkit.isOwnedByCurrentRegion(new Location(actor.getWorld(), x, p.y(), z), 1);
            }

            public String type(Pos q) {
              return Bukkit.isOwnedByCurrentRegion(location(q), 1)
                  ? location(q).getBlock().getType().name()
                  : "UNKNOWN";
            }

            public String blockData(Pos q) {
              return Bukkit.isOwnedByCurrentRegion(location(q), 1)
                  ? location(q).getBlock().getBlockData().getAsString()
                  : null;
            }
          };
      Set<Pos> protectedBlocks = new HashSet<>(village.layoutOccupancy());
      for (int x = -1; x <= 1; x++)
        for (int y = -1; y <= 1; y++)
          for (int z = -1; z <= 1; z++) {
            Pos q = p.add(x, y, z);
            if (plugin.playerProtected(village, q)) protectedBlocks.add(q);
          }
      if ((!learnedClutter
              && (!NavigationTerrain.salvageable(live, p)
                  || NavigationTerrain.architectureNear(live, p, protectedBlocks)))
          || !BlockRules.dry(b)
          || !BlockRules.safeMining(b))
        return blocked("natural_obstacle_no_longer_safe_to_clear", p, map);
      if (!plugin.mayChange(actor, b, "CLEAR_ROUTE"))
        return blocked("route_clearance_cancelled_by_protection", p, map);
      if (!b.getType().name().equals(map.cell(p).material())
          || BlockObservation.dangerous(b.getType().name(), b.getBlockData().getAsString()))
        return blocked("clear_target_changed_during_permission_event", p, map);
      Collection<ItemStack> drops = b.getDrops(new ItemStack(Material.AIR));
      if (!InventoryOps.canFit(actor.getInventory(), List.copyOf(drops)))
        return blocked("inventory_full_for_obstacle_drops", p, map);
      String material = b.getType().name();
      b.setType(Material.AIR, false);
      if (!b.getType().isAir()) return blocked("obstacle_removal_did_not_persist", p, map);
      drops.forEach(item -> actor.getInventory().addItem(item));
      actor.swingMainHand();
      next = now + plugin.workMillis();
      plugin.debug(
          village.id(),
          actor.getUniqueId().toString(),
          "navigation_obstacle",
          Map.of(
              "position",
              p,
              "action",
              "clear",
              "material",
              material,
              "inventory",
              InventoryOps.summary(actor.getInventory()),
              "verified",
              true));
      return new Result(false, true, "");
    }
    return new Result(true, opened, "");
  }

  private Map<String, Object> detail(String reason, Pos p, NavigationMap map) {
    var value = new NavigationObservation(actor).block(p, map);
    value.put("action_reason", reason);
    value.put("actor_distance_squared", actor.getLocation().distanceSquared(location(p)));
    value.put("inventory", InventoryOps.summary(actor.getInventory()));
    value.put("village_reserved", village.gatherProtected(p));
    value.put("player_protected", plugin.playerProtected(village, p));
    return value;
  }

  private Result blocked(String reason, Pos p, NavigationMap map) {
    return new Result(false, false, reason, detail(reason, p, map));
  }

  private Result waiting(String reason, Pos p, NavigationMap map) {
    return new Result(false, false, "", detail(reason, p, map));
  }
}
