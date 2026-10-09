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
    return work(plugin, actor, job, block, false);
  }

  /** Shore clearance may touch surface water, but never excavates below it or releases lava. */
  static String escape(CivilizationsPlugin plugin, Villager actor, Job job, Block block) {
    return work(plugin, actor, job, block, true);
  }

  private static String work(
      CivilizationsPlugin plugin, Villager actor, Job job, Block block, boolean surfaceEscape) {
    if (block.getType().isAir()) return null;
    boolean tree =
        "natural-tree".equals(job.blockData)
            && (job.expected.endsWith("_LOG") && !job.expected.startsWith("STRIPPED_")
                || job.expected.endsWith("_LEAVES")
                    && !block.getBlockData().getAsString().contains("persistent=true"));
    if (!block.getType().name().equals(job.expected))
      return "Site clearance changed at "
          + job.target.key()
          + ": expected="
          + job.expected
          + ", observed="
          + block.getBlockData().getAsString();
    if ((!SiteMaterials.clearable(job.expected)
        && !tree
        && !(plugin.experiments() != null
            && BlockObservation.learnedClear(
                plugin.experiments().rules(), actor.getUniqueId().toString(), block))))
      return "Site clearance needs removal classification at "
          + job.target.key()
          + ": "
          + block.getBlockData().getAsString();
    if (!(surfaceEscape ? safeSurfaceWater(block) : BlockRules.dry(block)))
      return "Site clearance would expose nearby fluid at " + job.target.key();
    if (!BlockRules.safeMining(block))
      return "Site clearance has a container or falling block above at "
          + job.target.key()
          + ": above="
          + block.getRelative(0, 1, 0).getType();
    String above = block.getRelative(0, 1, 0).getType().name();
    if (!block.getRelative(0, 1, 0).isPassable()
        && !(tree && (above.endsWith("_LOG") || above.endsWith("_LEAVES"))))
      return "Clearance would undermine an overhead block at " + job.target.key();
    List<ItemStack> drops = new ArrayList<>(block.getDrops());
    if (!InventoryOps.canFit(actor.getInventory(), drops))
      return "No room for site-clearance drops; resources retained";
    BlockLessons.remember(plugin, actor, block, "clearance_observation");
    block.setType(Material.AIR, false);
    if (!block.getType().isAir()) return "Site clearance did not persist";
    drops.forEach(item -> actor.getInventory().addItem(item));
    actor.swingMainHand();
    return null;
  }

  private static boolean safeSurfaceWater(Block block) {
    for (int x = -1; x <= 1; x++)
      for (int z = -1; z <= 1; z++)
        for (int y = -1; y <= 1; y++) {
          Block neighbor = block.getRelative(x, y, z);
          if (BlockObservation.dangerous(
              neighbor.getType().name(), neighbor.getBlockData().getAsString())) return false;
          if (neighbor.getBlockData() instanceof org.bukkit.block.data.Waterlogged w
              && w.isWaterlogged()) return false;
          if (neighbor.isLiquid() && (neighbor.getType() != Material.WATER || y > 0)) return false;
        }
    return !block.isLiquid();
  }
}
