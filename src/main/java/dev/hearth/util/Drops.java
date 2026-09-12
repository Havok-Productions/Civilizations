package dev.hearth.util;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Simulated mining drops. Hearth does not use vanilla block-break drop tables
 * (villagers would need tools); instead it "mines" a block and hands the
 * expected item(s) straight to the villager's inventory. This keeps the
 * economy honest: one ore block yields the vanilla-equivalent item.
 */
public final class Drops {

    private Drops() {
    }

    private static final Map<Material, Material> ORE_TO_ITEM = new HashMap<>();

    static {
        ORE_TO_ITEM.put(Material.COAL_ORE, Material.COAL);
        ORE_TO_ITEM.put(Material.DEEPSLATE_COAL_ORE, Material.COAL);
        ORE_TO_ITEM.put(Material.IRON_ORE, Material.RAW_IRON);
        ORE_TO_ITEM.put(Material.DEEPSLATE_IRON_ORE, Material.RAW_IRON);
        ORE_TO_ITEM.put(Material.COPPER_ORE, Material.RAW_COPPER);
        ORE_TO_ITEM.put(Material.DEEPSLATE_COPPER_ORE, Material.RAW_COPPER);
        ORE_TO_ITEM.put(Material.GOLD_ORE, Material.RAW_GOLD);
        ORE_TO_ITEM.put(Material.DEEPSLATE_GOLD_ORE, Material.RAW_GOLD);
        ORE_TO_ITEM.put(Material.REDSTONE_ORE, Material.REDSTONE);
        ORE_TO_ITEM.put(Material.DEEPSLATE_REDSTONE_ORE, Material.REDSTONE);
        ORE_TO_ITEM.put(Material.LAPIS_ORE, Material.LAPIS_LAZULI);
        ORE_TO_ITEM.put(Material.DEEPSLATE_LAPIS_ORE, Material.LAPIS_LAZULI);
        ORE_TO_ITEM.put(Material.DIAMOND_ORE, Material.DIAMOND);
        ORE_TO_ITEM.put(Material.DEEPSLATE_DIAMOND_ORE, Material.DIAMOND);
        ORE_TO_ITEM.put(Material.EMERALD_ORE, Material.EMERALD);
        ORE_TO_ITEM.put(Material.DEEPSLATE_EMERALD_ORE, Material.EMERALD);
        ORE_TO_ITEM.put(Material.NETHER_GOLD_ORE, Material.RAW_GOLD);
        ORE_TO_ITEM.put(Material.NETHER_QUARTZ_ORE, Material.QUARTZ);
        ORE_TO_ITEM.put(Material.AMETHYST_CLUSTER, Material.AMETHYST_SHARD);
    }

    /**
     * The item(s) a block would drop when mined by a villager.
     * For stone/dirt this returns the block itself (so villagers can rebuild walls).
     */
    public static Material dropFor(Material block, Random rng) {
        Material ore = ORE_TO_ITEM.get(block);
        if (ore != null) {
            return ore;
        }
        // Stone-family blocks drop themselves (or cobblestone in vanilla; we keep it simple).
        if (block == Material.STONE) {
            return Material.COBBLESTONE;
        }
        if (block == Material.GRASS_BLOCK) {
            return Material.DIRT;
        }
        if (block == Material.DEEPSLATE) {
            return Material.DEEPSLATE;
        }
        // Default: the block itself (dirt, sand, log, glowstone, etc.)
        return block;
    }

    /**
     * How many items of that drop a single block yields. Most are 1; a few are 4.
     */
    public static int dropAmount(Material block) {
        if (block == Material.REDSTONE_ORE || block == Material.DEEPSLATE_REDSTONE_ORE) {
            return 4;
        }
        if (block == Material.LAPIS_ORE || block == Material.DEEPSLATE_LAPIS_ORE) {
            return 4;
        }
        if (block == Material.AMETHYST_CLUSTER) {
            return 4;
        }
        return 1;
    }

    /**
     * Is this material an ore worth mining for?
     */
    public static boolean isOre(Material type) {
        return ORE_TO_ITEM.containsKey(type);
    }

    /**
     * Which surface block should a villager mine to obtain the given building material?
     * e.g. COBBLESTONE <- STONE, DIRT <- GRASS_BLOCK, GLOWSTONE <- GLOWSTONE, OAK_LOG <- OAK_LOG.
     */
    public static Material sourceBlock(Material target) {
        if (target == Material.COBBLESTONE) {
            return Material.STONE;
        }
        if (target == Material.DIRT) {
            return Material.GRASS_BLOCK;
        }
        if (target == Material.SAND) {
            return Material.SAND;
        }
        // Otherwise the villager mines the target block type directly (glowstone, logs, ...).
        return target;
    }
}
