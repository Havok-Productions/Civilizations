package dev.hearth.build;

import dev.hearth.HearthPlugin;
import dev.hearth.village.Village;
import dev.hearth.region.RegionIO;
import dev.hearth.util.BlockUtils;

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
        World world = village.getWorld();
        Location c = village.getCenter();
        int radius = village.getRadius();
        Material material = plugin.lightMaterial();
        int minLevel = plugin.lightMinLevel();

        // Collect every candidate column (ring just inside the wall, plus the
        // chest patch) in order.
        List<int[]> cols = new ArrayList<>();
        if (plugin.lightWallRing()) {
            int r = Math.max(4, radius - 2);
            for (int a = 0; a < 360; a += 22) {
                double rad = Math.toRadians(a);
                int x = c.getBlockX() + (int) Math.round(Math.cos(rad) * r);
                int z = c.getBlockZ() + (int) Math.round(Math.sin(rad) * r);
                cols.add(new int[]{x, z});
            }
        }
        if (plugin.lightChest() && village.getChestLocation() != null) {
            Location chest = village.getChestLocation();
            cols.add(new int[]{chest.getBlockX(), chest.getBlockZ()});
            cols.add(new int[]{chest.getBlockX() + 1, chest.getBlockZ()});
            cols.add(new int[]{chest.getBlockX(), chest.getBlockZ() + 1});
        }
        if (cols.isEmpty()) {
            return List.of();
        }

        // Folia (v1.3.2): group the columns by chunk and read them all in ONE
        // batched cross-region wait instead of one sequential wait per column.
        java.util.Map<Long, List<int[]>> byChunk = new java.util.LinkedHashMap<>();
        for (int[] col : cols) {
            byChunk.computeIfAbsent(RegionIO.chunkKey(col[0] >> 4, col[1] >> 4), k -> new ArrayList<>()).add(col);
        }
        java.util.Map<Long, java.util.function.Supplier<List<Integer>>> reads = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<Long, List<int[]>> e : byChunk.entrySet()) {
            List<int[]> chunkCols = e.getValue();
            reads.put(e.getKey(), () -> {
                List<Integer> ys = new ArrayList<>();
                for (int[] col : chunkCols) {
                    int x = col[0], z = col[1];
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
                        ys.add(null);
                        continue;
                    }
                    int floorY = floor.getY();
                    // Light goes on the floor (glowstone) if the block above is air and dark.
                    Block target = world.getBlockAt(x, floorY + 1, z);
                    if (!BlockUtils.isReplaceable(target.getType())) {
                        ys.add(null);
                        continue;
                    }
                    if (floor.getLightLevel() >= minLevel) {
                        ys.add(null);
                        continue;
                    }
                    ys.add(target.getY());
                }
                return ys;
            });
        }
        java.util.Map<Long, List<Integer>> results = RegionIO.inChunks(plugin, world, reads, null);

        // Reassemble in the original column order (ring, then chest patch).
        List<BuildJob> jobs = new ArrayList<>();
        for (java.util.Map.Entry<Long, List<int[]>> e : byChunk.entrySet()) {
            List<int[]> chunkCols = e.getValue();
            List<Integer> ys = results.get(e.getKey());
            if (ys == null) {
                continue; // chunk unavailable; the next planning tick retries
            }
            for (int i = 0; i < chunkCols.size() && i < ys.size(); i++) {
                Integer targetY = ys.get(i);
                if (targetY != null) {
                    jobs.add(new BuildJob(new Location(world, chunkCols.get(i)[0], targetY, chunkCols.get(i)[1]),
                            material, BuildJob.Kind.PLACE));
                }
            }
        }
        return jobs;
    }

}
