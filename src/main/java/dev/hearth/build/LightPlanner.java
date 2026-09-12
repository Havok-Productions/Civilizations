package dev.hearth.build;

import dev.hearth.HearthPlugin;
import dev.hearth.village.Village;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans lighting for the village: a ring just inside the wall, a patch
 * around the community chest, and the mine ceiling (handled by MinePlanner).
 *
 * <p>Uses {@code lightMaterial} (default GLOWSTONE, light level 15) so no
 * crafting is needed. Only places lights where the local light level is
 * below {@code lightMinLevel}.
 */
public class LightPlanner {

    private final HearthPlugin plugin;

    public LightPlanner(HearthPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Plan light jobs for a village. Returns an (possibly empty) list of PLACE jobs.
     */
    public List<BuildJob> plan(Village village) {
        List<BuildJob> jobs = new ArrayList<>();
        World world = village.getWorld();
        Location c = village.getCenter();
        int radius = village.getRadius();
        Material material = plugin.lightMaterial();
        int minLevel = plugin.lightMinLevel();

        if (plugin.lightWallRing()) {
            // Ring just inside the wall (radius - 2).
            int r = Math.max(4, radius - 2);
            int step = 8;
            for (int a = 0; a < 360; a += 22) {
                double rad = Math.toRadians(a);
                int x = c.getBlockX() + (int) Math.round(Math.cos(rad) * r);
                int z = c.getBlockZ() + (int) Math.round(Math.sin(rad) * r);
                considerLight(jobs, world, x, z, material, minLevel);
            }
        }

        if (plugin.lightChest() && village.getChestLocation() != null) {
            Location chest = village.getChestLocation();
            considerLight(jobs, world, chest.getBlockX(), chest.getBlockZ(), material, minLevel);
            considerLight(jobs, world, chest.getBlockX() + 1, chest.getBlockZ(), material, minLevel);
            considerLight(jobs, world, chest.getBlockX(), chest.getBlockZ() + 1, material, minLevel);
        }

        return jobs;
    }

    /**
     * Add a light job at (x, z) if the spot is dark and the ceiling is placeable.
     */
    private void considerLight(List<BuildJob> jobs, World world, int x, int z, Material material, int minLevel) {
        // Find the highest solid block at this column (the floor).
        Block floor = null;
        int y = world.getMaxHeight();
        while (y > world.getMinHeight()) {
            Block b = world.getBlockAt(x, y, z);
            if (b.getType().isSolid()) {
                floor = b;
                break;
            }
            y--;
        }
        if (floor == null) {
            return;
        }
        int floorY = floor.getY();
        // Light goes on the floor (glowstone) if the block above is air and dark.
        Block target = world.getBlockAt(x, floorY + 1, z);
        if (!dev.hearth.util.BlockUtils.isReplaceable(target.getType())) {
            return;
        }
        if (floor.getLightLevel() >= minLevel) {
            return;
        }
        jobs.add(new BuildJob(target.getLocation(), material, BuildJob.Kind.PLACE));
    }
}
