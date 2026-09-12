package dev.hearth.village;

import dev.hearth.HearthPlugin;
import dev.hearth.ai.AIAdvice;
import dev.hearth.brain.TaskType;
import dev.hearth.build.MinePlanner;
import dev.hearth.build.WallPlanner;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Villager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A village: a cluster of villagers, their beds, their wall, their mine,
 * and their community chest.
 *
 * <p>All state is plain data; the plugin tick loop drives updates.
 */
public class Village {

    private final UUID id;
    private final String name;
    private final World world;
    private volatile Location center; // swapped by discovery; read by brain/region threads
    private int radius;

    // Folia: these are read by per-villager brain threads and written by the village
    // coordination task / discovery. Reference swaps are made volatile and the member
    // set is concurrent, so no cross-region mutation races.
    private final Set<UUID> villagerIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile List<Location> beds = java.util.List.of();
    private volatile Location chestLocation;
    private volatile Location bookshelfLocation; // v1.2: the village's shared book (on the shelf)
    private volatile dev.hearth.social.VillageBook book; // v1.2: lazy, one per village

    private volatile List<dev.hearth.build.BuildJob> wallJobs = new ArrayList<>();
    private int wallBuilt = 0; // guarded by wallBuiltLock
    private final Object wallBuiltLock = new Object();
    private volatile MinePlanner.Mine mine;

    private volatile TaskType currentTask;
    private volatile boolean taskForced;
    private volatile long taskForcedAt;
    private volatile AIAdvice lastAdvice;
    private volatile long lastAdviceAt;

    private volatile int nearbyMonsters = 0;
    private volatile int wallIntegrityCache = 100;

    // Cached expensive queries (refreshed every ~30s, not every tick).
    private volatile boolean wallPlanTried;
    private volatile boolean darkCache;
    private volatile long darkSampledAt;
    private volatile java.util.List<dev.hearth.build.BuildJob> lightJobs = new java.util.ArrayList<>();
    private volatile long lightPlannedAt;

    // Folia: the village's region-bound coordination task (started by the plugin).
    private volatile boolean taskScheduled = false;
    private volatile io.papermc.paper.threadedregions.scheduler.ScheduledTask villageTask;

    public Village(UUID id, String name, World world, Location center, int radius) {
        this.id = id;
        this.name = name;
        this.world = world;
        this.center = center;
        this.radius = radius;
    }

    // ---- identity ----

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public World getWorld() {
        return world;
    }

    public Location getCenter() {
        return center;
    }

    public void setCenter(Location center) {
        this.center = center;
    }

    public int getRadius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = radius;
    }

    // ---- members ----

    public Set<UUID> getVillagerUuids() {
        return villagerIds;
    }

    public void addVillager(UUID id) {
        villagerIds.add(id);
    }

    public void removeVillager(UUID id) {
        villagerIds.remove(id);
    }

    public List<Villager> getVillagers() {
        List<Villager> out = new ArrayList<>();
        for (UUID id : villagerIds) {
            org.bukkit.entity.Entity e = world.getEntity(id);
            if (e instanceof Villager v && v.isValid() && !v.isDead()) {
                out.add(v);
            }
        }
        return out;
    }

    public List<Location> getBeds() {
        return beds;
    }

    public void setBeds(List<Location> beds) {
        // Swap the reference (never mutate in place) so readers on other region
        // threads keep a consistent snapshot.
        this.beds = java.util.List.copyOf(beds);
    }

    // ---- chest / bookshelf / book (v1.2 "society") ----

    public Location getChestLocation() {
        return chestLocation;
    }

    public Location getBookshelfLocation() {
        return bookshelfLocation;
    }

    public void setBookshelfLocation(Location bookshelfLocation) {
        this.bookshelfLocation = bookshelfLocation;
    }

    /**
     * The village's shared ledger (the book on the bookshelf). Lazily created
     * on first access; creation is plain file I/O (no world/entity access),
     * which is Folia-safe from any thread. Returns null only if the plugin
     * instance is gone (shutdown).
     */
    public dev.hearth.social.VillageBook getBook() {
        dev.hearth.social.VillageBook b = book;
        if (b == null) {
            synchronized (this) {
                b = book;
                if (b == null) {
                    HearthPlugin p = HearthPlugin.get();
                    if (p == null) {
                        return null;
                    }
                    b = new dev.hearth.social.VillageBook(
                            new File(p.getDataFolder(), "books"), id, p.societyBookMaxEntries());
                    b.setVillageName(name);
                    book = b;
                }
            }
        }
        return b;
    }

    public void setChestLocation(Location chestLocation) {
        this.chestLocation = chestLocation;
    }

    /**
     * Summary of chest contents (material name -> amount) for the AI report.
     */
    public Map<String, Integer> getChestContentsSummary() {
        Map<String, Integer> out = new HashMap<>();
        if (chestLocation == null || !(chestLocation.getBlock().getState() instanceof org.bukkit.block.Chest c)) {
            return out;
        }
        for (org.bukkit.inventory.ItemStack item : c.getBlockInventory()) {
            if (item != null) {
                out.merge(item.getType().name().toLowerCase(), item.getAmount(), Integer::sum);
            }
        }
        return out;
    }

    // ---- wall ----

    public List<dev.hearth.build.BuildJob> getWallJobs() {
        return wallJobs;
    }

    public void setWallJobs(List<dev.hearth.build.BuildJob> wallJobs) {
        this.wallJobs = wallJobs;
        this.wallBuilt = 0;
        this.wallPlanTried = true;
    }

    public int getWallBuilt() {
        synchronized (wallBuiltLock) {
            return wallBuilt;
        }
    }

    public void setWallBuilt(int wallBuilt) {
        synchronized (wallBuiltLock) {
            this.wallBuilt = wallBuilt;
        }
    }

    public void markWallBlockBuilt(int total) {
        synchronized (wallBuiltLock) {
            this.wallBuilt = Math.min(total, this.wallBuilt + 1);
        }
    }

    public double getWallIntegrity() {
        if (wallJobs.isEmpty()) {
            return 1.0;
        }
        return Math.min(1.0, (double) wallBuilt / wallJobs.size());
    }

    // ---- mine ----

    public MinePlanner.Mine getMine() {
        return mine;
    }

    public void setMine(MinePlanner.Mine mine) {
        this.mine = mine;
    }

    // ---- task / AI ----

    public TaskType getCurrentTask() {
        return currentTask;
    }

    public void setCurrentTask(TaskType currentTask) {
        this.currentTask = currentTask;
    }

    public boolean isTaskForced() {
        // Forced tasks expire after 5 minutes so the village returns to normal.
        if (taskForced && System.currentTimeMillis() - taskForcedAt > 300_000L) {
            taskForced = false;
            return false;
        }
        return taskForced;
    }

    public void setTaskForced(boolean taskForced) {
        this.taskForced = taskForced;
        this.taskForcedAt = System.currentTimeMillis();
    }

    // ---- cached queries ----

    public boolean isWallPlanTried() {
        return wallPlanTried;
    }

    public void setWallPlanTried(boolean b) {
        this.wallPlanTried = b;
    }

    public boolean isDarkCached() {
        return darkCache;
    }

    public void setDarkCached(boolean dark) {
        this.darkCache = dark;
        this.darkSampledAt = System.currentTimeMillis();
    }

    public long getDarkSampledAt() {
        return darkSampledAt;
    }

    public java.util.List<dev.hearth.build.BuildJob> getLightJobs() {
        return lightJobs;
    }

    public void setLightJobs(java.util.List<dev.hearth.build.BuildJob> lightJobs) {
        this.lightJobs = lightJobs;
        this.lightPlannedAt = System.currentTimeMillis();
    }

    public long getLightPlannedAt() {
        return lightPlannedAt;
    }

    public AIAdvice getLastAdvice() {
        return lastAdvice;
    }

    public void setLastAdvice(AIAdvice lastAdvice) {
        this.lastAdvice = lastAdvice;
        this.lastAdviceAt = System.currentTimeMillis();
    }

    public long getLastAdviceAt() {
        return lastAdviceAt;
    }

    // ---- stats ----

    public int getNearbyMonsters() {
        return nearbyMonsters;
    }

    public void setNearbyMonsters(int nearbyMonsters) {
        this.nearbyMonsters = nearbyMonsters;
    }

    public int getWallIntegrityCache() {
        return wallIntegrityCache;
    }

    public void setWallIntegrityCache(int wallIntegrityCache) {
        this.wallIntegrityCache = wallIntegrityCache;
    }

    // ---- Folia scheduling handle ----

    public boolean isTaskScheduled() {
        return taskScheduled;
    }

    public void setTaskScheduled(boolean taskScheduled) {
        this.taskScheduled = taskScheduled;
    }

    public io.papermc.paper.threadedregions.scheduler.ScheduledTask getVillageTask() {
        return villageTask;
    }

    public void setVillageTask(io.papermc.paper.threadedregions.scheduler.ScheduledTask villageTask) {
        this.villageTask = villageTask;
    }

    // ---- per-tick update (called from the village's region task) ----

    public void tick(HearthPlugin plugin) {
        // 1. Count nearby monsters.
        int monsters = 0;
        for (org.bukkit.entity.Entity e : world.getEntities()) {
            if (e instanceof org.bukkit.entity.Monster m && !m.isDead()) {
                if (m.getLocation().distanceSquared(center) <= (radius + 16) * (radius + 16)) {
                    monsters++;
                }
            }
        }
        setNearbyMonsters(monsters);

        // 2. If the wall is not yet planned, plan it (once; not every tick).
        if (plugin.wallsAutoBuild() && wallJobs.isEmpty() && !isWallPlanTried()) {
            setWallPlanTried(true);
            List<dev.hearth.build.BuildJob> jobs = plugin.wallPlanner().plan(this);
            if (!jobs.isEmpty()) {
                setWallJobs(jobs);
            }
        }

        // 3. If the wall is planned but not built and integrity is low, mark for repair.
        double integrity = getWallIntegrity();
        setWallIntegrityCache((int) (integrity * 100));

        // 4. Resolve the current top-priority task (local rules + optional AI).
        TaskType resolved = null;
        if (!isTaskForced()) {
            resolved = plugin.priorityPolicy().resolve(this, plugin);
            if (resolved != null) {
                TaskType previous = currentTask;
                setCurrentTask(resolved);
                // v1.2: record the plan in the village book when it changes.
                if (plugin.societyEnabled() && previous != resolved) {
                    dev.hearth.social.VillageBook b = getBook();
                    if (b != null) {
                        b.append(dev.hearth.social.VillageBook.Kind.PLAN, "village",
                                "the village is now focused on " + resolved.name().toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
        }

        // 5. v1.2: society upkeep — bookshelf next to the chest, stale-need cleanup.
        if (plugin.societyEnabled()) {
            if (plugin.societyBookshelf() && chestLocation != null && bookshelfLocation == null) {
                plugin.bookshelfManager().ensureBookshelf(this);
            }
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastStaleReleaseAt > 60_000L) {
                lastStaleReleaseAt = nowMs;
                dev.hearth.social.VillageBook b = getBook();
                if (b != null) {
                    b.releaseStale(nowMs, plugin.societyNeedStaleMs());
                }
            }
        }
    }

    private volatile long lastStaleReleaseAt;

    @Override
    public String toString() {
        return name + " (r=" + radius + ", " + villagerIds.size() + " villagers)";
    }
}
