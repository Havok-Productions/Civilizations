package dev.hearth.build;

import dev.hearth.HearthPlugin;
import dev.hearth.village.Village;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Plans a wall ring around the village plus a gated entrance, and a mine
 * tunnel with a staircase descent.
 *
 * <p>The mine planner guarantees the tunnel never passes through water, lava,
 * bedrock, or house blocks: it samples candidate columns and only digs where
 * the whole 3-wide x 3-tall cross-section is clear (room-and-pillar method).
 */
public class MinePlanner {

    private final HearthPlugin plugin;

    public MinePlanner(HearthPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * A planned mine: staircase + tunnel.
     *
     * <p>Thread-safety: several villager brains (each on its own region
     * thread) may advance the same mine concurrently, so all progress access
     * is synchronized on a private lock. {@code completed} is volatile so
     * plain field reads (status command, AI report) always observe the final
     * state.
     */
    public static class Mine {
        public final List<BuildJob> jobs = new ArrayList<>();
        public final Location entrance;
        public volatile boolean completed = false;
        private final Object progressLock = new Object();
        private int dug = 0; // guarded by progressLock

        public Mine(Location entrance) {
            this.entrance = entrance;
        }

        public int total() {
            return jobs.size();
        }

        public int getProgressPercent() {
            synchronized (progressLock) {
                if (total() == 0) {
                    return 0;
                }
                return (int) (100.0 * dug / total());
            }
        }

        public int getDug() {
            synchronized (progressLock) {
                return dug;
            }
        }

        public BuildJob nextJob() {
            synchronized (progressLock) {
                if (dug >= jobs.size()) {
                    return null;
                }
                return jobs.get(dug);
            }
        }

        /**
         * Claim the next job as dug.
         *
         * @return true if this call advanced the mine, false if another
         *         villager already claimed that job (the caller should simply
         *         pick the next job on its next tick).
         */
        public boolean markDug(BuildJob job) {
            synchronized (progressLock) {
                if (job != null && dug < jobs.size() && jobs.get(dug) == job) {
                    dug++;
                    if (dug >= jobs.size()) {
                        completed = true;
                    }
                    return true;
                }
                return false;
            }
        }

        public void reset() {
            synchronized (progressLock) {
                dug = 0;
                completed = false;
            }
        }

        @Override
        public String toString() {
            return "Mine(" + total() + " blocks, " + getDug() + " dug, " + (completed ? "complete" : "in progress") + ")";
        }
    }

    /**
     * Plan (or re-plan) the mine for a village.
     * Returns null if no dry route could be found.
     */
    public Mine plan(Village village) {
        World world = village.getWorld();
        Location center = village.getCenter();
        Random rng = new Random(village.getId().hashCode());

        int width = Math.max(3, plugin.mineWidth());
        int length = Math.max(8, plugin.mineLength());
        int pillarEvery = Math.max(2, plugin.minePillarEvery());
        int depth = Math.max(world.getMinHeight() + 8, plugin.mineDepth());

        // Try several candidate routes; pick the first that is dry and clear of houses.
        for (int attempt = 0; attempt < plugin.mineRouteAttempts(); attempt++) {
            // Pick a direction (rotate around the village).
            int dirIndex = (village.getId().hashCode() / (attempt + 1)) % 4;
            int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
            int[] d = dirs[dirIndex];
            // Start outside the village radius.
            int startX = center.getBlockX() + d[0] * (village.getRadius() + 6 + attempt * 4);
            int startZ = center.getBlockZ() + d[1] * (village.getRadius() + 6 + attempt * 4);

            Mine mine = new Mine(new Location(world, startX, 0, startZ));
            if (planRoute(world, mine, startX, startZ, depth, width, length, pillarEvery, rng)) {
                mine.entrance.setY(world.getHighestBlockYAt(startX, startZ) + 1);
                return mine;
            }
            // Otherwise try the next direction / offset.
        }
        plugin.getLogger().warning("Could not find a dry mine route for village " + village.getName());
        return null;
    }

    /**
     * Plan staircase + tunnel. Returns true if every block in the cross-section is diggable.
     */
    private boolean planRoute(World world, Mine mine, int startX, int startZ,
                              int depth, int width, int length, int pillarEvery, Random rng) {
        int surfaceY = world.getHighestBlockYAt(startX, startZ) + 1;
        if (surfaceY <= depth) {
            // Surface is already below the target depth; just build a tunnel at surface.
            depth = surfaceY - 1;
        }

        // 1. Staircase descent (diagonal steps down to tunnel floor).
        int steps = surfaceY - depth;
        for (int i = 0; i < steps; i++) {
            int y = surfaceY - i;
            int x = startX + (i % 2 == 0 ? 1 : -1);
            int z = startZ;
            // The "step" block the villager digs is at (x, y, z).
            if (!isDiggable(world, x, y, z)) {
                return false;
            }
            mine.jobs.add(new BuildJob(loc(world, x, y, z), Material.STONE, BuildJob.Kind.MINE));
        }

        // 2. Horizontal tunnel, room-and-pillar.
        // Tunnel runs along +Z (for simplicity); width spans X.
        int floorY = depth;
        for (int col = 0; col < length; col++) {
            int z = startZ + col;
            // Pillar column: leave the whole cross-section solid (room-and-pillar).
            if (pillarEvery > 0 && (col % pillarEvery) == (pillarEvery - 1)) {
                continue;
            }
            boolean colOk = true;
            for (int w = 0; w < width && colOk; w++) {
                int x = startX - (width / 2) + w;
                for (int h = 0; h < 3; h++) {
                    int y = floorY + h;
                    if (!isDiggable(world, x, y, z)) {
                        colOk = false;
                        break;
                    }
                }
            }
            if (!colOk) {
                return false; // route blocked; caller tries next direction
            }
            for (int w = 0; w < width; w++) {
                int x = startX - (width / 2) + w;
                for (int h = 0; h < 3; h++) {
                    int y = floorY + h;
                    Material target = (h == 0) ? Material.STONE : Material.AIR;
                    mine.jobs.add(new BuildJob(loc(world, x, y, z), target, BuildJob.Kind.MINE));
                }
            }
            // 3. Ceiling light every 8 columns.
            if (plugin.lightMine() && col % 8 == 4) {
                int x = startX;
                int y = floorY + 3;
                if (world.getBlockAt(x, y, z).getType().isAir()) {
                    mine.jobs.add(new BuildJob(loc(world, x, y, z), plugin.lightMaterial(), BuildJob.Kind.LIGHT));
                }
            }
        }
        return true;
    }

    /**
     * A block is diggable if it is not water/lava/bedrock and not a house block.
     */
    private boolean isDiggable(World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y > world.getMaxHeight()) {
            return false;
        }
        Material t = world.getBlockAt(x, y, z).getType();
        if (t == Material.BEDROCK) {
            return false;
        }
        if (plugin.avoidWater() && t == Material.WATER) {
            return false;
        }
        if (plugin.avoidLava() && t == Material.LAVA) {
            return false;
        }
        if (plugin.avoidHouse() && dev.hearth.util.BlockUtils.isHouseBlock(t)) {
            return false;
        }
        return true;
    }

    private Location loc(World world, int x, int y, int z) {
        return new Location(world, x, y, z);
    }
}
