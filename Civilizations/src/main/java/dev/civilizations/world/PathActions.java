package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import java.util.Set;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Villager;

/** A normal shovel path action, not arbitrary free placement of blocks. */
public final class PathActions {
  public static String work(CivilizationsPlugin plugin, Villager actor, Block ground) {
    if (ground.getType() == Material.DIRT_PATH) return null;
    if (!Set.of(Material.DIRT, Material.GRASS_BLOCK).contains(ground.getType())
        || !ground.getRelative(BlockFace.UP).isPassable()
        || !ground.getRelative(0, 2, 0).isPassable()
        || !BlockRules.dry(ground.getRelative(BlockFace.UP)))
      return "Path ground or access changed";
    if (!plugin.mayChange(actor, ground, "PATH")) return "Path protected";
    ground.setType(Material.DIRT_PATH, false);
    actor.swingMainHand();
    return null;
  }
}
