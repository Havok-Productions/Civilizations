package dev.hearth.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.HashSet;
import java.util.Set;

/**
 * Block-related helpers used by planners and brains.
 */
public final class BlockUtils {

    private BlockUtils() {
    }

    /**
     * Blocks that clearly belong to a "house" or player structure.
     * The mine planner refuses to dig through these so it never breaks into a building.
     */
    private static final Set<Material> HOUSE_BLOCKS = new HashSet<>();

    static {
        // Wood / planks / glass / doors / windows / furniture
        addAll("OAK_PLANKS", "SPRUCE_PLANKS", "BIRCH_PLANKS", "JUNGLE_PLANKS", "ACACIA_PLANKS", "DARK_OAK_PLANKS",
                "MANGROVE_PLANKS", "CHERRY_PLANKS", "BAMBOO_PLANKS", "CRIMSON_PLANKS", "WARPED_PLANKS",
                "OAK_LOG", "SPRUCE_LOG", "BIRCH_LOG", "JUNGLE_LOG", "ACACIA_LOG", "DARK_OAK_LOG",
                "MANGROVE_LOG", "CHERRY_LOG", "CRIMSON_STEM", "WARPED_STEM",
                "GLASS", "WHITE_STAINED_GLASS", "LIGHT_BLUE_STAINED_GLASS", "OAK_DOOR", "SPRUCE_DOOR", "BIRCH_DOOR",
                "JUNGLE_DOOR", "ACACIA_DOOR", "DARK_OAK_DOOR", "MANGROVE_DOOR", "CHERRY_DOOR", "IRON_DOOR",
                "TRAPDOOR", "OAK_TRAPDOOR", "SPRUCE_TRAPDOOR", "SIGN", "OAK_SIGN", "CHEST", "TRAPPED_CHEST",
                "BED", "WHITE_BED", "RED_BED", "LADDER", "FENCE", "OAK_FENCE", "SPRUCE_FENCE", "CARPET", "WHITE_CARPET",
                "TORCH", "SOUL_TORCH", "LANTERN", "SOUL_LANTERN", "CANDLE", "WHITE_CANDLE",
                "CRAFTING_TABLE", "FURNACE", "STONECUTTER", "SMOKER", "BLAST_FURNACE", "BARREL", "HOPPER",
                "BUTTON", "OAK_BUTTON", "LEVER", "PRESSURE_PLATE", "OAK_PRESSURE_PLATE",
                "WOOL", "WHITE_WOOL", "RED_WOOL", "BRICKS", "STONE_BRICKS", "MOSSY_STONE_BRICKS", "CRACKED_STONE_BRICKS",
                "CHISEL_STONE_BRICKS", "SMOOTH_STONE", "POLISHED_STONE", "STONE_STAIRS", "OAK_STAIRS", "COBBLESTONE_STAIRS",
                "SLAB", "STONE_SLAB", "OAK_SLAB", "COBBLESTONE_SLAB", "LIGHT_BLUE_SLAB",
                "POTATOES", "CARROTS", "WHEAT", "BEETROOTS", "MELON_STEM", "PUMPKIN_STEM", "COCOA", "SUGAR_CANE",
                "NETHERRACK", "SOUL_SAND", "SOUL_SOIL", "OBSIDIAN", "NETHER_BRICKS", "GLISTENING_ROOTS");
    }

    private static void addAll(String... names) {
        for (String name : names) {
            try {
                HOUSE_BLOCKS.add(Material.matchMaterial(name));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public static boolean isHouseBlock(Material type) {
        return HOUSE_BLOCKS.contains(type);
    }

    /**
     * Water or lava. (The Material.isLiquid()/isReplaceable() helpers were
     * removed from the modern Paper/Folia API, so we define what we need.)
     */
    public static boolean isLiquid(Material t) {
        return t == Material.WATER || t == Material.LAVA;
    }

    /**
     * A soft/replaceable block: air, water, or something non-solid such as
     * grass, crops, torches or saplings. Entities can occupy these blocks.
     */
    public static boolean isReplaceable(Material t) {
        return t.isAir() || isLiquid(t) || !t.isSolid();
    }

    /**
     * Any leaf block (oak, spruce, cherry, ...).
     */
    public static boolean isLeaves(Material t) {
        return t.name().endsWith("_LEAVES");
    }

    /**
     * A block is "walkable" if a villager can stand inside it (air, water, replaceable)
     * and there is solid ground below (or it is swimming).
     */
    public static boolean isStandable(World world, int x, int y, int z) {
        Block b = world.getBlockAt(x, y, z);
        Material t = b.getType();
        if (!isReplaceable(t)) {
            return false;
        }
        Material below = world.getBlockAt(x, y - 1, z).getType();
        if (!below.isSolid() && !isLiquid(below)) {
            // Could be standing on a non-solid like a slab - allow it.
            if (below == Material.AIR) {
                return false;
            }
        }
        return true;
    }

    /**
     * A block is "passable" for pathfinding if the entity can occupy it.
     * Water is passable (swim), lava is never, leaves are passable (break),
     * cobwebs are slow, fire is never.
     */
    public static boolean isPassable(Material type) {
        if (type == Material.LAVA || type == Material.FIRE) {
            return false;
        }
        if (isReplaceable(type)) {
            return true;
        }
        // Leaves, cobweb, vines: passable but slow.
        if (isLeaves(type) || type == Material.COBWEB || type == Material.VINE) {
            return true;
        }
        return false;
    }

    /**
     * Movement cost multiplier for entering a block of the given type.
     */
    public static double movementCost(Material type) {
        if (type == Material.LAVA || type == Material.FIRE) {
            return Double.POSITIVE_INFINITY;
        }
        if (isLiquid(type)) {
            return 3.0;
        }
        if (isLeaves(type) || type == Material.COBWEB || type == Material.VINE) {
            return 5.0;
        }
        if (isReplaceable(type)) {
            return 1.0;
        }
        return 1.5;
    }

    /**
     * Is the location inside a chunk that is loaded (entities ticked)?
     */
    public static boolean chunkLoaded(World world, int x, int z) {
        try {
            if (!world.isChunkLoaded(x, z)) {
                return false;
            }
            return world.getChunkAt(x >> 4, z >> 4).isEntitiesLoaded();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * Is there a hostile mob standing in this block (for path re-routing)?
     */
    public static boolean mobOccupying(World world, int x, int y, int z, int radius) {
        Location at = new Location(world, x + 0.5, y, z + 0.5);
        for (Entity e : world.getEntities()) {
            if (e instanceof LivingEntity le && !le.isDead()) {
                Location l = e.getLocation();
                if (Math.abs(l.getBlockX() - x) <= radius && Math.abs(l.getBlockY() - y) <= radius
                        && Math.abs(l.getBlockZ() - z) <= radius) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Distance from a location to the village center (used for "local" work).
     */
    public static double distanceTo(Location a, Location b) {
        return a.distance(b);
    }
}
