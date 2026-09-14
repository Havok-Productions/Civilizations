package dev.hearth.village;

import dev.hearth.HearthPlugin;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Bed;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.hearth.region.RegionIO;

/**
 * Detects villages by clustering villagers (flood fill within {@code radius}),
 * and keeps their state up to date.
 *
 * <p>Detection is cheap: it only runs when a villager spawns or on the
 * configured rescan interval, and it never does unbounded world scans.
 *
 * <p>Folia threading: the village map is a {@link ConcurrentHashMap} because
 * it is read by brain threads (villager region threads) while discovery
 * (global region thread) may re-register entries. Rescan only <em>reads</em>
 * the world; the one write it can trigger (community chest placement) is
 * region-routed by {@link ChestManager}. Rescans <em>merge</em> into existing
 * villages instead of creating duplicates, so a village's chest, wall, mine,
 * and scheduled region task survive every rescan.
 */
public class VillageManager {

    private final HearthPlugin plugin;
    private final Map<UUID, Village> villages = new ConcurrentHashMap<>();

    public VillageManager(HearthPlugin plugin) {
        this.plugin = plugin;
    }

    public List<Village> getVillages() {
        return new ArrayList<>(villages.values());
    }

    public Village byId(UUID id) {
        return villages.get(id);
    }

    /**
     * Find the village a villager belongs to (by proximity to the center), or null.
     */
    public Village forVillager(Villager v) {
        Location here = v.getLocation();
        Village best = null;
        double bestD = Double.MAX_VALUE;
        for (Village village : villages.values()) {
            if (!village.getWorld().equals(here.getWorld())) {
                continue;
            }
            double d = village.getCenter().distanceSquared(here);
            if (d < bestD) {
                bestD = d;
                best = village;
            }
        }
        if (best != null && bestD <= (best.getRadius() + 8) * (best.getRadius() + 8)) {
            best.addVillager(v.getUniqueId());
            return best;
        }
        return null;
    }

    /**
     * Called when a villager is seen (spawn, etc.).
     */
    public void onVillagerSeen(Villager v) {
        Village existing = forVillager(v);
        if (existing == null) {
            // Not near any village yet; may become the seed of a new one.
            ensureVillageFor(v);
        }
    }

    private void ensureVillageFor(Villager v) {
        // Find or create a village centered on this villager. Reuse an
        // existing nearby village (up to 2x radius away) instead of creating a
        // duplicate settlement.
        Location here = v.getLocation();
        Village near = findNearbyExisting(here.getWorld(), here);
        if (near != null) {
            near.addVillager(v.getUniqueId());
            return;
        }
        // Create a new village.
        UUID id = UUID.nameUUIDFromBytes(("hearth:" + here.getWorld().getName() + ":" + here.getBlockX() + ":" + here.getBlockZ()).getBytes());
        Village village = villages.computeIfAbsent(id, k -> new Village(k, "Village-" + (villages.size() + 1), here.getWorld(), here.clone(), plugin.villageRadius()));
        village.addVillager(v.getUniqueId());
        village.setCenter(recomputeCenter(village));
    }

    /**
     * Find an already-registered village in {@code world} whose center is
     * within {@code 2 x villageRadius} of {@code seed}, or null. Used to merge
     * a newly detected cluster into an existing settlement instead of
     * duplicating it.
     */
    private Village findNearbyExisting(World world, Location seed) {
        double limit = plugin.villageRadius() * 2.0;
        Village best = null;
        double bestD = Double.MAX_VALUE;
        for (Village village : villages.values()) {
            if (!village.getWorld().equals(world)) {
                continue;
            }
            double d = village.getCenter().distanceSquared(seed);
            if (d <= limit * limit && d < bestD) {
                bestD = d;
                best = village;
            }
        }
        return best;
    }

    /**
     * Re-detect and refine villages (called on a slow timer).
     */
    public void rescan() {
        // Collect all living villagers.
        Map<World, List<Villager>> byWorld = new HashMap<>();
        for (World w : org.bukkit.Bukkit.getWorlds()) {
            List<Villager> list = new ArrayList<>();
            for (org.bukkit.entity.Entity e : w.getEntities()) {
                if (e instanceof Villager v && !v.isDead()) {
                    list.add(v);
                }
            }
            if (!list.isEmpty()) {
                byWorld.put(w, list);
            }
        }

        for (Map.Entry<World, List<Villager>> entry : byWorld.entrySet()) {
            World w = entry.getKey();
            List<Villager> villagers = entry.getValue();
            if (villagers.isEmpty()) {
                continue;
            }

            // Simple clustering: greedy nearest-centroid.
            List<Village> local = new ArrayList<>();
            for (Villager v : villagers) {
                Village placed = null;
                double bestD = plugin.villageRadius() * plugin.villageRadius();
                for (Village village : local) {
                    double d = village.getCenter().distanceSquared(v.getLocation());
                    if (d < bestD) {
                        bestD = d;
                        placed = village;
                    }
                }
                if (placed == null) {
                    UUID id = UUID.nameUUIDFromBytes(("hearth:" + w.getName() + ":" + v.getLocation().getBlockX() + ":" + v.getLocation().getBlockZ()).getBytes());
                    placed = new Village(id, "Village-" + (local.size() + 1), w, v.getLocation().clone(), plugin.villageRadius());
                    local.add(placed);
                }
                placed.addVillager(v.getUniqueId());
            }

            // Refine centers, then either register the cluster or merge it
            // into the existing settlement it belongs to. Merging preserves the
            // existing village's chest, wall, mine, and region-bound task.
            for (Village cluster : local) {
                cluster.setCenter(recomputeCenter(cluster));
                collectBeds(cluster);
                Village existing = findNearbyExisting(w, cluster.getCenter());
                if (existing != null) {
                    existing.setCenter(cluster.getCenter());
                    existing.setBeds(cluster.getBeds());
                    for (UUID id : cluster.getVillagerUuids()) {
                        existing.addVillager(id);
                    }
                } else {
                    // put (not putIfAbsent): if an entry with this id exists it
                    // is a stale village at this same seed position; replace it.
                    villages.put(cluster.getId(), cluster);
                }
            }
        }

        // Ensure each village has a chest (if configured). ChestManager routes
        // the placement onto the chest block's region thread, so this is safe
        // even though rescan itself runs on the global region thread.
        if (plugin.chestAutoPlace()) {
            for (Village village : villages.values()) {
                if (village.getChestLocation() == null) {
                    plugin.chestManager().ensureChest(village);
                }
            }
        }
    }

    private Location recomputeCenter(Village village) {
        double sx = 0, sy = 0, sz = 0;
        int n = 0;
        for (Villager v : village.getVillagers()) {
            sx += v.getLocation().getX();
            sy += v.getLocation().getY();
            sz += v.getLocation().getZ();
            n++;
        }
        if (n == 0) {
            return village.getCenter();
        }
        return new Location(village.getWorld(), sx / n, sy / n, sz / n);
    }

    private void collectBeds(Village village) {
        List<Location> beds = new ArrayList<>();
        Location c = village.getCenter();
        int r = village.getRadius() + 4;
        World world = village.getWorld();
        // Beds live near the surface: sample a coarse grid and check the
        // highest block plus a few below (covers one- and two-story houses).
        //
        // Folia: group the sampled columns by chunk and run each chunk's scan
        // on that chunk's owning region (RegionIO). One cross-region round
        // trip per chunk — reading from a foreign region thread is what
        // crashed the old code ("Cannot retrieve chunk asynchronously").
        Map<Long, List<int[]>> byChunk = new LinkedHashMap<>();
        for (int x = c.getBlockX() - r; x <= c.getBlockX() + r; x += 3) {
            for (int z = c.getBlockZ() - r; z <= c.getBlockZ() + r; z += 3) {
                byChunk.computeIfAbsent(RegionIO.chunkKey(x >> 4, z >> 4), k -> new ArrayList<>())
                        .add(new int[]{x, z});
            }
        }
        // Folia (v1.3.2): dispatch one read per chunk, then make ONE bounded
        // wait for all of them (RegionIO.inChunks) instead of N sequential
        // per-chunk waits — the per-thread circuit breaker additionally bounds
        // total blocking, so the calling thread (the global region) can never
        // stall a Folia tick region the way the old loop did.
        Map<Long, java.util.function.Supplier<List<Location>>> reads = new LinkedHashMap<>();
        for (Map.Entry<Long, List<int[]>> e : byChunk.entrySet()) {
            List<int[]> cols = e.getValue();
            reads.put(e.getKey(), () -> {
                List<Location> local = new ArrayList<>();
                for (int[] col : cols) {
                    int x = col[0], z = col[1];
                    int topY;
                    try {
                        topY = world.getHighestBlockYAt(x, z);
                    } catch (IllegalArgumentException ex) {
                        continue;
                    }
                    for (int y = topY; y >= Math.max(world.getMinHeight(), topY - 6); y--) {
                        org.bukkit.block.Block b = world.getBlockAt(x, y, z);
                        if (b.getState() instanceof Bed) {
                            local.add(b.getLocation());
                        }
                    }
                }
                return local;
            });
        }
        for (List<Location> l : RegionIO.inChunks(plugin, world, reads, null).values()) {
            if (l != null) {
                beds.addAll(l);
            }
        }
        village.setBeds(beds);
    }
}
