package dev.hearth.village;

import dev.hearth.HearthPlugin;
import dev.hearth.util.BlockUtils;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataType;

import java.util.Random;

/**
 * Ensures every village has a bookshelf — the physical "library" the
 * village book belongs to.
 *
 * <p>Behavior mirrors {@link ChestManager}:
 * <ol>
 *   <li>Look for an existing bookshelf tagged (PersistentDataContainer) with
 *       this village's id within {@code society.bookshelf-max-distance}.</li>
 *   <li>Otherwise find a spot near the community chest (falling back to the
 *       village center) and place a BOOKSHELF, tagged for future rescans.</li>
 * </ol>
 *
 * <p>Folia-safety: the searches are read-only (legal from any thread) and the
 * placement write is routed onto the target block's region thread via
 * {@link HearthPlugin#runInRegion}, where the world state is re-validated
 * before writing.
 */
public class BookshelfManager {

    private final HearthPlugin plugin;

    public BookshelfManager(HearthPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Make sure the village has a bookshelf. Returns the location when one
     * already exists, or null while placement is in flight / no spot found
     * (the next village tick retries).
     */
    public Location ensureBookshelf(Village village) {
        if (village.getBookshelfLocation() != null) {
            return village.getBookshelfLocation();
        }
        // 1. Existing tagged bookshelf nearby (read-only).
        Location found = findTaggedBookshelf(village);
        if (found != null) {
            village.setBookshelfLocation(found);
            return found;
        }
        // 2. Find a spot (read-only), place on the block's region thread.
        Location spot = findSpot(village);
        if (spot == null) {
            return null; // village tick will retry; not an error worth logging every pass
        }
        plugin.runInRegion(spot, () -> placeBookshelf(village, spot));
        return null;
    }

    /**
     * Runs on the region thread that owns {@code spot} (guaranteed by
     * {@code runInRegion}); re-validates the world state before writing.
     */
    private void placeBookshelf(Village village, Location spot) {
        if (village.getBookshelfLocation() != null) {
            return; // another thread placed it first
        }
        Block block = spot.getBlock();
        if (!BlockUtils.isReplaceable(block.getType())) {
            return; // no longer placeable; retried on the next village tick
        }
        Block below = block.getRelative(0, -1, 0);
        if (!below.getType().isSolid()) {
            return;
        }
        // A bookshelf wants air above it too (a villager stands in front, not on top).
        if (!BlockUtils.isReplaceable(block.getRelative(0, 1, 0).getType())) {
            return;
        }
        block.setType(Material.BOOKSHELF);
        if (!(block.getState() instanceof org.bukkit.block.Shelf shelfState)) {
            return;
        }
        shelfState.getPersistentDataContainer().set(plugin.bookshelfKey(),
                PersistentDataType.STRING, village.getId().toString());
        village.setBookshelfLocation(block.getLocation());
        plugin.getLogger().info("Placed village bookshelf for " + village.getName() + " at "
                + block.getLocation().getBlockX() + "," + block.getLocation().getBlockY() + "," + block.getLocation().getBlockZ());
    }

    private Location findTaggedBookshelf(Village village) {
        World world = village.getWorld();
        Location anchor = village.getChestLocation() != null ? village.getChestLocation() : village.getCenter();
        int maxD = plugin.societyBookshelfMaxDistance();
        for (int x = anchor.getBlockX() - maxD; x <= anchor.getBlockX() + maxD; x += 2) {
            for (int z = anchor.getBlockZ() - maxD; z <= anchor.getBlockZ() + maxD; z += 2) {
                int topY;
                try {
                    topY = world.getHighestBlockYAt(x, z);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                for (int y = topY; y >= Math.max(world.getMinHeight(), topY - 4); y--) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType() != Material.BOOKSHELF) {
                        continue;
                    }
                    if (!(b.getState() instanceof org.bukkit.block.Shelf shelfState)) {
                        continue;
                    }
                    String id = shelfState.getPersistentDataContainer().get(plugin.bookshelfKey(), PersistentDataType.STRING);
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
        Location anchor = village.getChestLocation() != null ? village.getChestLocation() : village.getCenter();
        int maxD = plugin.societyBookshelfMaxDistance();
        Random rng = new Random(village.getId().hashCode());
        // Rings around the anchor (the chest), starting close — the bookshelf
        // belongs next to the village's common room, not across the map.
        for (int ring = 1; ring <= maxD; ring += 1) {
            int angles = 8;
            for (int a = 0; a < angles; a++) {
                double rad = (2 * Math.PI * a) / angles + rng.nextDouble() * 0.1;
                int x = anchor.getBlockX() + (int) Math.round(Math.cos(rad) * ring);
                int z = anchor.getBlockZ() + (int) Math.round(Math.sin(rad) * ring);
                int topY;
                try {
                    topY = world.getHighestBlockYAt(x, z);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                // Solid floor, air at the bookshelf slot and above it.
                Block floor = world.getBlockAt(x, topY, z);
                if (!floor.getType().isSolid()) {
                    continue;
                }
                if (!BlockUtils.isReplaceable(world.getBlockAt(x, topY + 1, z).getType())) {
                    continue;
                }
                if (!BlockUtils.isReplaceable(world.getBlockAt(x, topY + 2, z).getType())) {
                    continue;
                }
                return new Location(world, x, topY + 1, z);
            }
        }
        return null;
    }
}
