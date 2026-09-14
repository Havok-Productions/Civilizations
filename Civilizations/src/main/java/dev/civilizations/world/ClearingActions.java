package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;

/** Executes only the exact soil/vegetation removal admitted by the site compiler. */
public final class ClearingActions {
  private ClearingActions() {}

  public static String work(CivilizationsPlugin plugin, Villager actor, Job job, Block block) {
    if (block.getType().isAir()) return null;
    if (!block.getType().name().equals(job.expected)
        || (!SiteMaterials.clearable(job.expected)
            && !(plugin.experiments() != null
                && BlockObservation.learnedClear(
                    plugin.experiments().rules(), actor.getUniqueId().toString(), block)))
        || !BlockRules.dry(block)
        || !BlockRules.safeMining(block))
      return "Site clearance changed or is unsafe at " + job.target.key();
    if (!block.getRelative(0, 1, 0).isPassable())
      return "Clearance would undermine an overhead block at " + job.target.key();
    List<ItemStack> drops = new ArrayList<>(block.getDrops());
    if (!InventoryOps.canFit(actor.getInventory(), drops))
      return "No room for site-clearance drops; resources retained";
    block.setType(Material.AIR, false);
    if (!block.getType().isAir()) return "Site clearance did not persist";
    drops.forEach(item -> actor.getInventory().addItem(item));
    actor.swingMainHand();
    return null;
  }
}
