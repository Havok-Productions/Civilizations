package dev.civilizations.world;

import java.util.Set;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.*;

/** Local block safety checks; callers must own the surrounding region. */
public final class BlockRules {
  private BlockRules() {}

  public static Material drop(Material m) {
    return switch (m) {
      case STONE -> Material.COBBLESTONE;
      case DEEPSLATE -> Material.COBBLED_DEEPSLATE;
      case COAL_ORE, DEEPSLATE_COAL_ORE -> Material.COAL;
      case IRON_ORE, DEEPSLATE_IRON_ORE -> Material.RAW_IRON;
      case COPPER_ORE, DEEPSLATE_COPPER_ORE -> Material.RAW_COPPER;
      case GRASS_BLOCK -> Material.DIRT;
      default -> m;
    };
  }

  public static boolean replaceable(Block b) {
    return dev.civilizations.core.SiteMaterials.vegetation(b.getType().name())
        || b.getType().isAir()
        || Set.of(
                Material.SHORT_GRASS,
                Material.TALL_GRASS,
                Material.FERN,
                Material.LARGE_FERN,
                Material.SNOW,
                Material.DANDELION,
                Material.POPPY)
            .contains(b.getType());
  }

  public static boolean natural(Block b) {
    return dev.civilizations.core.HarvestCatalog.mineral(b.getType().name())
        || Set.of(
                Material.STONE,
                Material.DEEPSLATE,
                Material.GRANITE,
                Material.DIORITE,
                Material.ANDESITE,
                Material.DIRT,
                Material.GRASS_BLOCK,
                Material.COAL_ORE,
                Material.DEEPSLATE_COAL_ORE,
                Material.IRON_ORE,
                Material.DEEPSLATE_IRON_ORE,
                Material.COPPER_ORE,
                Material.DEEPSLATE_COPPER_ORE)
            .contains(b.getType());
  }

  public static boolean dry(Block b) {
    for (int x = -1; x <= 1; x++)
      for (int y = -1; y <= 1; y++)
        for (int z = -1; z <= 1; z++) {
          Block n = b.getRelative(x, y, z);
          if (n.isLiquid() || n.getBlockData() instanceof Waterlogged w && w.isWaterlogged())
            return false;
        }
    return true;
  }

  public static boolean safeMining(Block b) {
    return !Set.of(Material.SAND, Material.RED_SAND, Material.GRAVEL)
            .contains(b.getRelative(BlockFace.UP).getType())
        && !(b.getState() instanceof Container);
  }

  public static boolean tree(Block b) {
    Block root = b;
    for (int i = 0;
        i < 8 && root.getRelative(BlockFace.DOWN).getType().name().endsWith("_LOG");
        i++) root = root.getRelative(BlockFace.DOWN);
    if (!Set.of(Material.GRASS_BLOCK, Material.DIRT, Material.PODZOL, Material.ROOTED_DIRT)
        .contains(root.getRelative(BlockFace.DOWN).getType())) return false;
    for (int y = 0; y <= 8; y++)
      for (int x = -2; x <= 2; x++)
        for (int z = -2; z <= 2; z++)
          if (b.getRelative(x, y, z).getType().name().endsWith("_LEAVES")) return true;
    return false;
  }
}
