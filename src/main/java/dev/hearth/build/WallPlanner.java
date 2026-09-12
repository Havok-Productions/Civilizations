package dev.hearth.build;

import dev.hearth.HearthPlugin;
import dev.hearth.village.Village;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans a rectangular wall ring around the village with a gated entrance.
 *
 * <p>Wall properties:
 * <ul>
 *   <li>One block thick, configurable height (default 4).</li>
 *   <li>Material mined directly by villagers (default STONE; use COBBLESTONE to mine stone).</li>
 *   <li>3-wide gate in the south wall with a door.</li>
 *   <li>Repair = scan the ring and re-queue any missing blocks.</li>
 * </ul>
 */
public class WallPlanner {

    private final HearthPlugin plugin;

    public WallPlanner(HearthPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Plan the wall ring for a village. Returns the list of build jobs (PLACE kind).
     */
    public List<BuildJob> plan(Village village) {
        World world = village.getWorld();
        Location c = village.getCenter();
        int radius = village.getRadius();
        int height = Math.max(2, plugin.wallHeight());
        Material material = plugin.wallMaterial();

        // Rectangle bounds (village center +/- radius).
        int minX = c.getBlockX() - radius;
        int maxX = c.getBlockX() + radius;
        int minZ = c.getBlockZ() - radius;
        int maxZ = c.getBlockZ() + radius;
        int y0 = world.getHighestBlockYAt(c.getBlockX(), c.getBlockZ()) + 1; // wall sits ON the ground

        List<BuildJob> jobs = new ArrayList<>();
        int gateStart = (minX + maxX) / 2 - 1;
        int gateEnd = (minX + maxX) / 2 + 1;

        // North wall (z = maxZ)
        for (int x = minX; x <= maxX; x++) {
            addWallColumn(jobs, world, x, y0, maxZ, height, material);
        }
        // South wall (z = minZ) with gate gap
        for (int x = minX; x <= maxX; x++) {
            if (plugin.wallGate() && x >= gateStart && x <= gateEnd) {
                continue; // leave the gate open
            }
            addWallColumn(jobs, world, x, y0, minZ, height, material);
        }
        // East wall (x = maxX), skip corners already covered
        for (int z = minZ + 1; z <= maxZ - 1; z++) {
            addWallColumn(jobs, world, maxX, y0, z, height, material);
        }
        // West wall (x = minX), skip corners
        for (int z = minZ + 1; z <= maxZ - 1; z++) {
            addWallColumn(jobs, world, minX, y0, z, height, material);
        }

        // Gate door in the middle of the gate gap (south wall).
        if (plugin.wallGate()) {
            int doorX = (minX + maxX) / 2;
            Material door = plugin.gateMaterial();
            jobs.add(new BuildJob(loc(world, doorX, y0, minZ), door, BuildJob.Kind.PLACE));
        }

        // Sort by distance from the gate so villagers build outward from the entrance.
        Location gate = loc(world, (minX + maxX) / 2, y0, minZ);
        jobs.sort((a, b) -> Double.compare(a.location.distanceSquared(gate), b.location.distanceSquared(gate)));

        village.setWallJobs(jobs);
        return jobs;
    }

    private void addWallColumn(List<BuildJob> jobs, World world, int x, int groundY, int z, int height, Material material) {
        for (int h = 0; h < height; h++) {
            int y = groundY + h; // groundY is already above the floor
            Location at = loc(world, x, y, z);
            // Only queue blocks that are actually missing.
            Material current = world.getBlockAt(x, y, z).getType();
            if (current == Material.AIR || dev.hearth.util.BlockUtils.isReplaceable(current)) {
                jobs.add(new BuildJob(at, material, BuildJob.Kind.PLACE));
            }
        }
    }

    /**
     * Scan the village wall ring and return the number of missing blocks.
     */
    public int countMissing(Village village) {
        List<BuildJob> jobs = village.getWallJobs();
        if (jobs == null) {
            return 0;
        }
        int missing = 0;
        for (BuildJob job : jobs) {
            Material t = job.location.getBlock().getType();
            if (t == Material.AIR || dev.hearth.util.BlockUtils.isReplaceable(t)) {
                missing++;
            }
        }
        return missing;
    }

    private Location loc(World world, int x, int y, int z) {
        return new Location(world, x, y, z);
    }
}
