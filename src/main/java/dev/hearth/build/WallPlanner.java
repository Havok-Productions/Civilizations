package dev.hearth.build;

import dev.hearth.HearthPlugin;
import dev.hearth.region.RegionIO;
import dev.hearth.village.Village;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *
 * <p>Folia-safe: every block read is routed onto the owning region of the
 * chunk it lives in ({@link RegionIO}), batched one round trip per chunk, so
 * planning works from any region thread (including the brain's).
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

        // Wall sits ON the ground (Folia-safe read of the center column).
        Integer baseY = RegionIO.inChunk(plugin, world, c.getBlockX(), c.getBlockZ(),
                () -> world.getHighestBlockYAt(c.getBlockX(), c.getBlockZ()), null);
        if (baseY == null) {
            // Cannot read the ground (region busy / shutting down); the next
            // village tick retries planning.
            return List.of();
        }
        int y0 = baseY + 1;

        // Collect every wall block position (gate gap skipped), in order.
        List<int[]> candidates = new ArrayList<>();
        int gateStart = (minX + maxX) / 2 - 1;
        int gateEnd = (minX + maxX) / 2 + 1;
        // North wall (z = maxZ)
        for (int x = minX; x <= maxX; x++) {
            addWallColumn(candidates, x, y0, maxZ, height);
        }
        // South wall (z = minZ) with gate gap
        for (int x = minX; x <= maxX; x++) {
            if (plugin.wallGate() && x >= gateStart && x <= gateEnd) {
                continue; // leave the gate open
            }
            addWallColumn(candidates, x, y0, minZ, height);
        }
        // East wall (x = maxX), skip corners already covered
        for (int z = minZ + 1; z <= maxZ - 1; z++) {
            addWallColumn(candidates, maxX, y0, z, height);
        }
        // West wall (x = minX), skip corners
        for (int z = minZ + 1; z <= maxZ - 1; z++) {
            addWallColumn(candidates, minX, y0, z, height);
        }

        // Only queue blocks that are actually missing. Batched per chunk:
        // one cross-region round trip per chunk instead of per block.
        Map<Long, List<int[]>> byChunk = new LinkedHashMap<>();
        for (int[] p : candidates) {
            byChunk.computeIfAbsent(RegionIO.chunkKey(p[0] >> 4, p[2] >> 4), k -> new ArrayList<>()).add(p);
        }
        // Folia (v1.3.2): one bounded wait for the whole wall scan.
        List<int[]> missing = new ArrayList<>();
        Map<Long, java.util.function.Supplier<List<int[]>>> reads = new LinkedHashMap<>();
        for (Map.Entry<Long, List<int[]>> e : byChunk.entrySet()) {
            List<int[]> pos = e.getValue();
            reads.put(e.getKey(), () -> {
                List<int[]> out = new ArrayList<>();
                for (int[] p : pos) {
                    Material current = world.getBlockAt(p[0], p[1], p[2]).getType();
                    if (current == Material.AIR || dev.hearth.util.BlockUtils.isReplaceable(current)) {
                        out.add(p);
                    }
                }
                return out;
            });
        }
        for (List<int[]> local : RegionIO.inChunks(plugin, world, reads, null).values()) {
            if (local != null) {
                missing.addAll(local);
            }
        }

        List<BuildJob> jobs = new ArrayList<>();
        for (int[] p : missing) {
            jobs.add(new BuildJob(loc(world, p[0], p[1], p[2]), material, BuildJob.Kind.PLACE));
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

    private void addWallColumn(List<int[]> candidates, int x, int groundY, int z, int height) {
        for (int h = 0; h < height; h++) {
            int y = groundY + h; // groundY is already above the floor
            candidates.add(new int[]{x, y, z});
        }
    }

    /**
     * Scan the village wall ring and return the number of missing blocks.
     * Folia-safe: batched per chunk via {@link RegionIO}.
     */
    public int countMissing(Village village) {
        List<BuildJob> jobs = village.getWallJobs();
        if (jobs == null || jobs.isEmpty()) {
            return 0;
        }
        Map<Long, List<BuildJob>> byChunk = new LinkedHashMap<>();
        for (BuildJob job : jobs) {
            Location l = job.location;
            byChunk.computeIfAbsent(RegionIO.chunkKey(l.getBlockX() >> 4, l.getBlockZ() >> 4), k -> new ArrayList<>()).add(job);
        }
        // Folia (v1.3.2): one bounded wait for the whole ring scan. All jobs
        // share the village's world, so a single batch is valid.
        int missing = 0;
        org.bukkit.World world = byChunk.values().iterator().next().get(0).location.getWorld();
        Map<Long, java.util.function.Supplier<Integer>> reads = new LinkedHashMap<>();
        for (Map.Entry<Long, List<BuildJob>> e : byChunk.entrySet()) {
            List<BuildJob> chunkJobs = e.getValue();
            reads.put(e.getKey(), () -> {
                int n = 0;
                for (BuildJob job : chunkJobs) {
                    Material t = job.location.getBlock().getType();
                    if (t == Material.AIR || dev.hearth.util.BlockUtils.isReplaceable(t)) {
                        n++;
                    }
                }
                return n;
            });
        }
        for (Integer n : RegionIO.inChunks(plugin, world, reads, 0).values()) {
            if (n != null) {
                missing += n;
            }
        }
        return missing;
    }

    private Location loc(World world, int x, int y, int z) {
        return new Location(world, x, y, z);
    }
}
