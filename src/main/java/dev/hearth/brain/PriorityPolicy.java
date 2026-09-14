package dev.hearth.brain;

import dev.hearth.HearthPlugin;
import dev.hearth.region.RegionIO;
import dev.hearth.village.Village;

/**
 * Priority policy: decides which task a village should do next.
 *
 * <p>Local rules always handle safety (sleep at night, repair under threat).
 * If the AI advisor is enabled, its advice (when fresh) wins over the
 * local heuristics for non-safety tasks.
 */
public class PriorityPolicy {

    private final HearthPlugin plugin;

    public PriorityPolicy(HearthPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Resolve the next task for a village.
     */
    public TaskType resolve(Village village, HearthPlugin plugin) {
        long time = village.getWorld().getTime();

        // --- Safety overrides (local rules always win) ---
        if (plugin.sleepEnabled() && isSleepWindow(time, plugin.sleepFrom(), plugin.sleepUntil())) {
            return TaskType.SLEEP;
        }
        if (village.getNearbyMonsters() >= 2 && village.getWallIntegrity() < 1.0) {
            return TaskType.REPAIR;
        }

        // --- AI advisor (if enabled and fresh) ---
        if (plugin.aiEnabled()) {
            // Fire-and-forget refresh if stale.
            if (village.getLastAdvice() == null || village.getLastAdvice().isStale(plugin.aiIntervalMinutes())) {
                plugin.aiAdvisor().advise(plugin, village, advice -> {
                    if (advice != null) {
                        village.setLastAdvice(advice);
                    }
                });
            }
            if (village.getLastAdvice() != null && !village.getLastAdvice().isStale(plugin.aiIntervalMinutes())) {
                TaskType fromAI = village.getLastAdvice().task;
                // Never let the AI cancel safety.
                if (fromAI != TaskType.SLEEP) {
                    return fromAI;
                }
            }
        }

        // --- Local heuristics (the "basic knowledge" every villager has) ---
        // 1. Shelter: community chest.
        if (village.getChestLocation() == null && plugin.chestAutoPlace()) {
            return TaskType.CHEST;
        }
        // 2. Defense: wall.
        double integrity = village.getWallIntegrity();
        if (village.getWallJobs().isEmpty() && plugin.wallsAutoBuild()) {
            return TaskType.WALL; // planner will fill it in on first wall job
        }
        if (integrity < plugin.wallRepairBelow()) {
            return TaskType.REPAIR;
        }
        if (integrity < 1.0 && plugin.wallsAutoBuild()) {
            return TaskType.WALL;
        }
        // 3. Light (darkness is sampled at most once every 30s and cached).
        if (village.getChestLocation() != null) {
            long now = System.currentTimeMillis();
            if (now - village.getDarkSampledAt() > 30_000L) {
                village.setDarkCached(sampleDarkness(village));
            }
            if (village.isDarkCached()) {
                return TaskType.LIGHT;
            }
        }
        // 4. Resources: gather building material if the wall wants it.
        int wallNeed = Math.max(0, village.getWallJobs().size() - village.getWallBuilt());
        if (wallNeed > 0 && stockpileOf(village, plugin.wallMaterial()) < wallNeed / 2) {
            return TaskType.GATHER;
        }
        // 5. Mine for ores once the village is safe.
        if (plugin.miningEnabled() && village.getMine() == null) {
            return TaskType.MINE;
        }
        if (plugin.miningEnabled() && village.getMine() != null && !village.getMine().completed) {
            return TaskType.MINE;
        }
        // 6. Rest.
        return TaskType.IDLE;
    }

    private boolean isSleepWindow(long time, long from, long until) {
        if (from <= until) {
            return time >= from && time < until;
        }
        // Window wraps midnight.
        return time >= from || time < until;
    }

    /**
     * Sample a few spots inside the village to see if it is dark.
     *
     * <p>Each sample is one read inside one chunk (highest block + light
     * level), so it is Folia-safe: the read runs on the chunk's owning region
     * when needed, with a bounded wait and a safe skip on failure.
     */
    private boolean sampleDarkness(Village village) {
        int dark = 0;
        int samples = 0;
        org.bukkit.World w = village.getWorld();
        // Folia (v1.3.2): read all sample columns in ONE batched cross-region
        // wait instead of eight sequential per-chunk waits.
        java.util.List<int[]> cols = new java.util.ArrayList<>();
        for (int a = 0; a < 360; a += 45) {
            double rad = Math.toRadians(a);
            int x = village.getCenter().getBlockX() + (int) Math.round(Math.cos(rad) * village.getRadius() * 0.6);
            int z = village.getCenter().getBlockZ() + (int) Math.round(Math.sin(rad) * village.getRadius() * 0.6);
            cols.add(new int[]{x, z});
        }
        java.util.Map<Long, java.util.List<int[]>> byChunk = new java.util.LinkedHashMap<>();
        for (int[] col : cols) {
            byChunk.computeIfAbsent(RegionIO.chunkKey(col[0] >> 4, col[1] >> 4), k -> new java.util.ArrayList<>()).add(col);
        }
        java.util.Map<Long, java.util.function.Supplier<java.util.List<Boolean>>> reads = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<Long, java.util.List<int[]>> e : byChunk.entrySet()) {
            java.util.List<int[]> chunkCols = e.getValue();
            reads.put(e.getKey(), () -> {
                java.util.List<Boolean> darkCols = new java.util.ArrayList<>();
                for (int[] col : chunkCols) {
                    int x = col[0], z = col[1];
                    org.bukkit.block.Block b;
                    try {
                        b = w.getHighestBlockAt(x, z);
                    } catch (IllegalArgumentException ex) {
                        darkCols.add(null);
                        continue;
                    }
                    darkCols.add(b.getLightLevel() < plugin.lightMinLevel());
                }
                return darkCols;
            });
        }
        java.util.Map<Long, java.util.List<Boolean>> results = RegionIO.inChunks(plugin, w, reads, null);
        for (java.util.Map.Entry<Long, java.util.List<int[]>> e : byChunk.entrySet()) {
            java.util.List<Boolean> darkCols = results.get(e.getKey());
            if (darkCols == null) {
                continue; // chunk unavailable; skip these samples
            }
            for (Boolean darkCol : darkCols) {
                if (darkCol == null) {
                    continue;
                }
                samples++;
                if (darkCol) {
                    dark++;
                }
            }
        }
        return samples > 0 && dark * 2 > samples;
    }

    /**
     * How many of the given material are in the community chest.
     */
    private int stockpileOf(Village village, org.bukkit.Material material) {
        return HearthPlugin.chestCount(material, village.getChestLocation());
    }
}
