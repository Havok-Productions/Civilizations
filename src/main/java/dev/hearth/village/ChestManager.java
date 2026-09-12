package dev.hearth.village;

import dev.hearth.HearthPlugin;
import dev.hearth.util.BlockUtils;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Random;

/**
 * Ensures every village has a community chest.
 *
 * <p>Behavior:
 * <ul>
 *   <li>First looks for an existing chest tagged with this village's id
 *       (via the PersistentDataContainer) within {@code chest.max-distance}.</li>
 *   <li>Otherwise finds a flat, air-backed spot near the village center and
 *       places a chest, then tags it so future rescans find it.</li>
 * </ul>
 */
public class ChestManager {

    private final HearthPlugin plugin;

    public ChestManager(HearthPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Make sure the village has a chest. Returns the chest location if one
     * already exists, or null if placement is still in flight / no spot was
     * found (the next rescan picks it up).
     *
     * <p>Folia-safe from any thread: the search is read-only (block reads are
     * legal cross-thread), and the placement write is routed onto the chest
     * block's region thread via {@link HearthPlugin#runInRegion}, where the
     * world state is re-validated before writing (see {@link #placeChest}).
     */
    public Location ensureChest(Village village) {
        if (village.getChestLocation() != null) {
            return village.getChestLocation();
        }
        // 1. Look for an existing tagged chest nearby (read-only).
        Location found = findTaggedChest(village);
        if (found != null) {
            village.setChestLocation(found);
            return found;
        }
        // 2. Find a spot (read-only), then place it on the block's region thread.
        Location spot = findSpot(village);
        if (spot == null) {
            plugin.getLogger().warning("Could not find a spot for a community chest in " + village.getName());
            return null;
        }
        plugin.runInRegion(spot, () -> placeChest(village, spot));
        // The village learns the location as soon as the placement lands on the
        // region thread; the brain tolerates a null chest until then (it simply
        // does not deposit yet).
        return null;
    }

    /**
     * Place and tag the community chest. Runs on the region thread that owns
     * the target block (guaranteed by {@code runInRegion}). Re-validates the
     * world state here, because the spot was found on a possibly-different
     * thread a moment ago and another actor may have changed the block since.
     */
    private void placeChest(Village village, Location spot) {
        if (village.getChestLocation() != null) {
            return; // another thread placed it first
        }
        Block block = spot.getBlock();
        // Need air at the chest position and a solid block below.
        if (!BlockUtils.isReplaceable(block.getType())) {
            return; // no longer placeable; the next rescan retries with a fresh spot
        }
        Block below = block.getRelative(0, -1, 0);
        if (!below.getType().isSolid()) {
            return;
        }
        block.setType(Material.CHEST);
        // Tag it so we can find it later (PDC lives on the block state in modern APIs).
        if (!(block.getState() instanceof org.bukkit.block.Chest chestState)) {
            return;
        }
        chestState.getPersistentDataContainer().set(plugin.chestKey(),
                PersistentDataType.STRING, village.getId().toString());
        Location chest = block.getLocation();
        village.setChestLocation(chest);
        plugin.getLogger().info("Placed community chest for " + village.getName() + " at " + chest.getBlockX() + "," + chest.getBlockY() + "," + chest.getBlockZ());
    }

    private Location findTaggedChest(Village village) {
        World world = village.getWorld();
        Location c = village.getCenter();
        int maxD = plugin.chestMaxDistance();
        // Search a modest area around the center.
        for (int x = c.getBlockX() - maxD; x <= c.getBlockX() + maxD; x += 2) {
            for (int z = c.getBlockZ() - maxD; z <= c.getBlockZ() + maxD; z += 2) {
                int topY;
                try {
                    topY = world.getHighestBlockYAt(x, z);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                for (int y = topY; y >= Math.max(world.getMinHeight(), topY - 4); y--) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType() != Material.CHEST) {
                        continue;
                    }
                    if (!(b.getState() instanceof org.bukkit.block.Chest chestState)) {
                        continue;
                    }
                    String id = chestState.getPersistentDataContainer().get(plugin.chestKey(), PersistentDataType.STRING);
                    if (village.getId().toString().equals(id)) {
                        return b.getLocation();
                    }
                }
            }
        }
        return null;
    }

    private Location findSpot(Village village) {
        World world = village.getWorld();
        Location c = village.getCenter();
        Random rng = new Random(village.getId().hashCode());
        // Try a ring of spots around the center, starting close.
        for (int ring = 2; ring <= plugin.chestMaxDistance(); ring += 2) {
            int angles = 8;
            for (int a = 0; a < angles; a++) {
                double rad = (2 * Math.PI * a) / angles + ring * 0.13;
                int x = c.getBlockX() + (int) Math.round(Math.cos(rad) * ring);
                int z = c.getBlockZ() + (int) Math.round(Math.sin(rad) * ring);
                int topY;
                try {
                    topY = world.getHighestBlockYAt(x, z);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                // Need: solid floor, air above it, air above that (for the villager to stand).
                Block floor = world.getBlockAt(x, topY, z);
                if (!floor.getType().isSolid()) {
                    continue;
                }
                Block above1 = world.getBlockAt(x, topY + 1, z);
                Block above2 = world.getBlockAt(x, topY + 2, z);
                if (!BlockUtils.isReplaceable(above1.getType())) {
                    continue;
                }
                if (!BlockUtils.isReplaceable(above2.getType())) {
                    continue;
                }
                return new Location(world, x, topY + 1, z);
            }
        }
        return null;
    }

    /**
     * All items currently in the village chest.
     */
    public ItemStack[] contents(Village village) {
        if (village.getChestLocation() == null
                || !(village.getChestLocation().getBlock().getState() instanceof org.bukkit.block.Chest c)) {
            return new ItemStack[0];
        }
        return c.getBlockInventory().getContents();
    }

    /**
     * Count items of a material in the chest.
     */
    public int count(Village village, Material material) {
        return dev.hearth.HearthPlugin.chestCount(material, village.getChestLocation());
    }
}
