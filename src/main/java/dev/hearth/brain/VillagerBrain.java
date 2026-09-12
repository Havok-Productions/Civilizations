package dev.hearth.brain;

import dev.hearth.HearthPlugin;
import dev.hearth.build.BuildJob;
import dev.hearth.build.MinePlanner;
import dev.hearth.path.PathResult;
import dev.hearth.path.PathStore;
import dev.hearth.util.BlockUtils;
import dev.hearth.util.Drops;
import dev.hearth.village.Village;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Bed;
import org.bukkit.block.Block;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The per-villager state machine.
 *
 * <pre>
 *  IDLE ──pick task──▶ TRAVEL ──arrive──▶ WORK ──done──▶ IDLE
 *    ▲                    │  ▲               │
 *    │                    ▼  │               ▼
 *    └────────────── FLEE ───┘           (carry → chest)
 *
 *  SLEEP is entered automatically in the sleep window and exits at dawn.
 * </pre>
 *
 * <p>Movement is handled by {@link dev.hearth.move.MovementController} (normal
 * walking speed). Pathfinding is requested via {@link PathStore} (budgeted A*).
 */
public class VillagerBrain {

    public enum OnArrive { WORK, DEPOSIT, SLEEP, FLEE_SAFE, IDLE }

    private final Villager villager;
    private final Random rng;

    private VillagerState state = VillagerState.IDLE;
    private Village village;

    private TaskType currentTask = TaskType.IDLE;
    private Location target;              // final destination of the current travel
    private OnArrive onArrive = OnArrive.IDLE;
    private Location pendingBed;          // bed to enter on arrival (sleep), if any
    private BuildJob currentJob;          // the build/mine job being worked
    private Material carryMaterial;       // what is being carried
    private int carryCount;               // units carried this trip

    private PathStore.Request pathRequest;
    private List<int[]> path;
    private int pathIndex;
    private int repathAttempts;

    /** Folia: the entity-scheduler task that ticks this brain (managed by the plugin). */
    private ScheduledTask task;

    /** A chest→inventory transfer is queued; wait for the item to land. */
    private boolean transferPending;

    // Stuck / progress tracking (used by MovementController)
    private Location lastPosition;
    private long lastMoveTime;
    private long stateSince;

    // Per-tick budgets
    private int readsLeft;
    private int writesLeft;

    private long lastIdleWander;

    public VillagerBrain(Villager villager) {
        this.villager = villager;
        this.rng = new Random(villager.getUniqueId().getLeastSignificantBits());
        this.lastPosition = villager.getLocation().clone();
        this.lastMoveTime = System.currentTimeMillis();
        this.stateSince = System.currentTimeMillis();
    }

    public Villager getVillager() {
        return villager;
    }

    public Village getVillage() {
        return village;
    }

    public ScheduledTask getTask() {
        return task;
    }

    public void setTask(ScheduledTask task) {
        this.task = task;
    }

    public PathStore.Request getPathRequest() {
        return pathRequest;
    }

    public VillagerState getState() {
        return state;
    }

    public Location getCurrentTarget() {
        if (path != null && pathIndex < path.size() && village != null) {
            int[] b = path.get(pathIndex);
            return new Location(village.getWorld(), b[0] + 0.5, b[1], b[2] + 0.5);
        }
        return target;
    }

    public TaskType getCurrentTask() {
        return currentTask;
    }

    public void arrived() {
        if (path == null) {
            onArrived();
            return;
        }
        pathIndex++;
        if (pathIndex >= path.size()) {
            path = null;
            onArrived();
        }
    }

    // ---- MovementController hooks ----

    public Location lastPosition() {
        return lastPosition;
    }

    public void lastPositionSet(Location loc) {
        this.lastPosition = loc;
    }

    public long lastMoveTime() {
        return lastMoveTime;
    }

    public void lastMoveTimeSet(long t) {
        this.lastMoveTime = t;
    }

    public void noteProgress() {
        this.lastMoveTime = System.currentTimeMillis();
    }

    // ---- Main tick ----

    public void tick(HearthPlugin plugin, long now) {
        if (!villager.isValid() || villager.isDead()) {
            return;
        }
        readsLeft = plugin.blockReadsPerVillager();
        writesLeft = plugin.blockWritesPerVillager();

        Village v = ensureVillage(plugin);
        if (v == null) {
            idleWander(plugin, now);
            return;
        }
        long time = v.getWorld().getTime();
        boolean sleepWindow = plugin.sleepEnabled() && isSleepWindow(time, plugin.sleepFrom(), plugin.sleepUntil());

        // Wake up at dawn: teleporting away from the bed ends the sleep animation.
        if (state == VillagerState.SLEEP && !sleepWindow) {
            if (villager.isSleeping()) {
                Location wake = homeSpot(v);
                villager.teleport(wake);
            }
            pendingBed = null;
            setState(VillagerState.IDLE);
            return;
        }

        // Night falls: everyone to bed (safety rule, overrides AI).
        if (sleepWindow && state != VillagerState.SLEEP && state != VillagerState.FLEE) {
            startSleep(v, plugin);
            return;
        }

        // Threat: flee to the safe spot.
        Monster m = HearthPlugin.nearestMonster(villager.getLocation(), plugin.fleeRadius());
        if (m != null && state != VillagerState.FLEE && state != VillagerState.SLEEP) {
            startFlee(v, plugin);
            return;
        }
        if (state == VillagerState.FLEE && m == null) {
            setState(VillagerState.IDLE);
            return;
        }
        if (state == VillagerState.FLEE) {
            return; // MovementController handles the travel.
        }

        switch (state) {
            case IDLE -> doIdle(plugin, v, now);
            case TRAVEL -> doTravel(plugin, v, now);
            case WORK -> doWork(plugin, v, now);
            case SLEEP -> {
                // Already asleep; nothing to do.
            }
            case FLEE -> {
                // Handled above.
            }
        }
    }

    private Village ensureVillage(HearthPlugin plugin) {
        if (village != null) {
            return village;
        }
        village = plugin.villageManager().forVillager(villager);
        return village;
    }

    private boolean isSleepWindow(long time, long from, long until) {
        if (from <= until) {
            return time >= from && time < until;
        }
        return time >= from || time < until;
    }

    private void setState(VillagerState s) {
        this.state = s;
        this.stateSince = System.currentTimeMillis();
        // Any pending chest transfer becomes irrelevant after a state change.
        this.transferPending = false;
    }

    // ---- IDLE ----

    private void doIdle(HearthPlugin plugin, Village v, long now) {
        // 1. Deliver carried goods (prefer a nearby wall job, else the chest).
        if (carryCount > 0) {
            BuildJob job = nextWallJobNear(v, 32, carryMaterial);
            if (job != null) {
                currentJob = job;
                currentTask = TaskType.WALL;
                startTravel(plugin, adjacentStandable(job.location), OnArrive.WORK);
                return;
            }
            if (v.getChestLocation() != null) {
                startTravel(plugin, adjacentStandable(v.getChestLocation()), OnArrive.DEPOSIT);
                return;
            }
        }

        // 2. Too far from the village? Walk back.
        double maxD = (v.getRadius() + 12) * (v.getRadius() + 12);
        if (villager.getLocation().distanceSquared(v.getCenter()) > maxD) {
            startTravel(plugin, v.getCenter(), OnArrive.IDLE);
            return;
        }

        // 3. Pick the village's current task.
        TaskType task = v.getCurrentTask() != null ? v.getCurrentTask() : TaskType.IDLE;
        executeTask(plugin, v, task);
    }

    private void executeTask(HearthPlugin plugin, Village v, TaskType task) {
        switch (task) {
            case CHEST -> {
                // The chest is placed synchronously by the ChestManager; the village
                // state updates immediately, so the villager just idles afterwards.
                plugin.chestManager().ensureChest(v);
                idleWander(plugin, System.currentTimeMillis());
            }
            case WALL, REPAIR -> {
                BuildJob job = nextMissingWallJob(v);
                if (job == null) {
                    idleWander(plugin, System.currentTimeMillis());
                    return;
                }
                currentJob = job;
                startTravel(plugin, adjacentStandable(job.location), OnArrive.WORK);
            }
            case LIGHT -> {
                // Light jobs are cached on the village (planned at most every 30s).
                long now = System.currentTimeMillis();
                if (v.getLightJobs().isEmpty() && now - v.getLightPlannedAt() > 30_000L) {
                    v.setLightJobs(plugin.lightPlanner().plan(v));
                }
                BuildJob job = nextMissingLightJob(v);
                if (job == null) {
                    idleWander(plugin, System.currentTimeMillis());
                    return;
                }
                currentJob = job;
                startTravel(plugin, adjacentStandable(job.location), OnArrive.WORK);
            }
            case GATHER -> {
                Material source = Drops.sourceBlock(plugin.wallMaterial());
                Location src = findNearestSourceBlock(v, source, 48);
                if (src == null) {
                    idleWander(plugin, System.currentTimeMillis());
                    return;
                }
                carryMaterial = plugin.wallMaterial();
                currentJob = new BuildJob(src.clone(), source, BuildJob.Kind.MINE);
                startTravel(plugin, adjacentStandable(src), OnArrive.WORK);
            }
            case MINE -> {
                MinePlanner.Mine mine = v.getMine();
                if (mine == null) {
                    mine = plugin.minePlanner().plan(v);
                    if (mine == null) {
                        idleWander(plugin, System.currentTimeMillis());
                        return;
                    }
                    v.setMine(mine);
                }
                BuildJob job = mine.nextJob();
                if (job == null) {
                    // Tunnel complete: mine ores near it.
                    Location ore = findOreNearTunnel(plugin, v);
                    if (ore == null) {
                        idleWander(plugin, System.currentTimeMillis());
                        return;
                    }
                    currentJob = new BuildJob(ore.clone(), ore.getBlock().getType(), BuildJob.Kind.MINE);
                } else {
                    currentJob = job;
                }
                startTravel(plugin, adjacentStandable(currentJob.location), OnArrive.WORK);
            }
            case SLEEP -> startSleep(v, plugin);
            case IDLE -> idleWander(plugin, System.currentTimeMillis());
        }
    }

    private void idleWander(HearthPlugin plugin, long now) {
        // Occasional small shuffle so villagers look alive (never a sprint).
        if (now - lastIdleWander > 4000 && rng.nextInt(3) == 0) {
            lastIdleWander = now;
            float yaw = (float) (rng.nextDouble() * Math.PI * 2);
            villager.setRotation(yaw, 0);
        }
    }

    // ---- TRAVEL ----

    private void startTravel(HearthPlugin plugin, Location dest, OnArrive on) {
        this.target = dest;
        this.onArrive = on;
        this.repathAttempts = 0;
        this.path = null;
        this.pathIndex = 0;
        PathStore ps = plugin.pathStore();
        if (pathRequest != null) {
            ps.cancel(pathRequest);
        }
        Location from = villager.getLocation();
        pathRequest = ps.request(from.getWorld(),
                new Location(from.getWorld(), from.getBlockX(), from.getBlockY(), from.getBlockZ()),
                new Location(dest.getWorld(), dest.getBlockX(), dest.getBlockY(), dest.getBlockZ()));
        setState(VillagerState.TRAVEL);
    }

    private void doTravel(HearthPlugin plugin, Village v, long now) {
        PathStore ps = plugin.pathStore();
        if (pathRequest != null) {
            // A result may be waiting (search finished) even though it is no longer "pending".
            PathResult r = ps.poll(pathRequest);
            if (r != null) {
                pathRequest = null;
                if (r.size() > 0) {
                    this.path = r.blocks;
                    this.pathIndex = 0;
                } else {
                    // No path found.
                    repathAttempts++;
                    if (repathAttempts >= 3) {
                        double d2 = villager.getLocation().distanceSquared(target);
                        if (d2 <= 49) {
                            // Close enough: take a natural step.
                            villager.teleport(target.clone().add(0, 0.01, 0));
                            onArrived();
                        } else {
                            setState(VillagerState.IDLE);
                        }
                    } else {
                        startTravel(plugin, target, onArrive);
                    }
                }
                return;
            }
            if (ps.isPending(pathRequest)) {
                // Still computing; MovementController nudges toward the goal as a fallback.
                return;
            }
            // Cancelled or expired.
            pathRequest = null;
        }
        // Timed out? Abort.
        if (now - stateSince > 30_000L) {
            setState(VillagerState.IDLE);
        }
    }

    private void onArrived() {
        switch (onArrive) {
            case WORK -> {
                setState(VillagerState.WORK);
            }
            case DEPOSIT -> {
                depositToChest();
                setState(VillagerState.IDLE);
            }
            case SLEEP -> {
                // Enter the bed (vanilla sleep animation) if we have one.
                if (pendingBed != null) {
                    pendingBed = villager.sleep(pendingBed) ? pendingBed : null;
                }
                setState(VillagerState.SLEEP);
            }
            case FLEE_SAFE -> setState(VillagerState.IDLE);
            case IDLE -> setState(VillagerState.IDLE);
        }
    }

    // ---- WORK ----

    private void doWork(HearthPlugin plugin, Village v, long now) {
        if (currentJob == null) {
            setState(VillagerState.IDLE);
            return;
        }
        switch (currentTask) {
            case WALL, REPAIR -> workWallJob(plugin, v);
            case LIGHT -> workLightJob(plugin, v);
            case GATHER -> workGather(plugin, v);
            case MINE -> workMine(plugin, v);
            default -> setState(VillagerState.IDLE);
        }
    }

    private void workWallJob(HearthPlugin plugin, Village v) {
        BuildJob job = nextMissingWallJob(v);
        if (job == null) {
            // Wall complete.
            v.setWallBuilt(v.getWallJobs().size());
            setState(VillagerState.IDLE);
            return;
        }
        Block target = job.location.getBlock();
        if (!target.getType().isAir() && !BlockUtils.isReplaceable(target.getType())) {
            // Already built (by a neighbor or a player).
            v.markWallBlockBuilt(v.getWallJobs().size());
            setState(VillagerState.IDLE);
            return;
        }
        Material need = job.material;

        // --- Material (Folia-safe: the chest write happens on the chest's region) ---
        if (invCount(need) == 0) {
            int inChest = HearthPlugin.chestCount(need, v.getChestLocation());
            if (transferPending) {
                if (inChest > 0) {
                    return; // transfer still in flight; wait one more tick
                }
                transferPending = false; // lost or consumed by a neighbor; re-decide below
            }
            if (inChest > 0) {
                if (!canCarry(1)) {
                    setState(VillagerState.IDLE); // inventory full; deliver first
                    return;
                }
                transferPending = true;
                plugin.transferFromChest(v.getChestLocation(), need, 1, this);
                return; // the item lands next tick; the brain re-checks then
            }
            // Go gather it.
            Material source = Drops.sourceBlock(need);
            Location src = findNearestSourceBlock(v, source, 48);
            if (src == null) {
                setState(VillagerState.IDLE); // nothing in reach; rest
                return;
            }
            carryMaterial = need;
            currentJob = new BuildJob(src.clone(), source, BuildJob.Kind.MINE);
            currentTask = TaskType.GATHER;
            startTravel(plugin, adjacentStandable(src), OnArrive.WORK);
            return;
        } else if (transferPending) {
            transferPending = false; // already have the item
        }

        // Place the block on its own region thread (Folia).
        if (writesLeft <= 0 || readsLeft <= 0) {
            return; // try next tick
        }
        writesLeft--;
        readsLeft--;
        plugin.runInRegion(target.getLocation(), () -> {
            if (target.getType().isAir() || BlockUtils.isReplaceable(target.getType())) {
                target.setType(need);
                target.getWorld().playSound(target.getLocation(), Sound.BLOCK_STONE_PLACE, 0.4f, 1.0f);
            }
        });
        consume(need, 1);
        carryCount = Math.max(0, carryCount - 1);
        v.markWallBlockBuilt(v.getWallJobs().size());
        setState(VillagerState.IDLE);
    }

    private void workLightJob(HearthPlugin plugin, Village v) {
        Material light = plugin.lightMaterial();
        Location spot = currentJob.location;
        Block target = spot.getBlock();
        if (!target.getType().isAir() && !BlockUtils.isReplaceable(target.getType())) {
            setState(VillagerState.IDLE);
            return;
        }

        if (light == Material.TORCH) {
            // Torch = stick + coal. Assemble if possible.
            if (invCount(Material.STICK) >= 1 && invCount(Material.COAL) >= 1) {
                consume(Material.STICK, 1);
                consume(Material.COAL, 1);
                give(Material.TORCH, 1);
            } else if (invCount(Material.STICK) < 1) {
                gatherComponent(plugin, v, Material.STICK, Material.OAK_LOG);
                return;
            } else {
                gatherComponent(plugin, v, Material.COAL, Material.COAL_ORE);
                return;
            }
        } else {
            if (invCount(light) == 0) {
                // Folia-safe: the chest write happens on the chest's region.
                int inChest = HearthPlugin.chestCount(light, v.getChestLocation());
                if (transferPending) {
                    if (inChest > 0) {
                        return; // transfer still in flight
                    }
                    transferPending = false;
                }
                if (inChest > 0) {
                    if (!canCarry(1)) {
                        setState(VillagerState.IDLE);
                        return;
                    }
                    transferPending = true;
                    plugin.transferFromChest(v.getChestLocation(), light, 1, this);
                    return; // the item lands next tick; the brain re-checks then
                }
                Location src = findNearestSourceBlock(v, light, 48);
                if (src == null) {
                    setState(VillagerState.IDLE);
                    return;
                }
                carryMaterial = light;
                currentJob = new BuildJob(src.clone(), light, BuildJob.Kind.MINE);
                currentTask = TaskType.GATHER;
                startTravel(plugin, adjacentStandable(src), OnArrive.WORK);
                return;
            } else if (transferPending) {
                transferPending = false;
            }
        }

        if (writesLeft <= 0 || readsLeft <= 0) {
            return;
        }
        writesLeft--;
        readsLeft--;
        plugin.runInRegion(target.getLocation(), () -> {
            if (target.getType().isAir() || BlockUtils.isReplaceable(target.getType())) {
                target.setType(light);
                target.getWorld().playSound(target.getLocation(), Sound.BLOCK_WOOD_PLACE, 0.4f, 1.0f);
            }
        });
        if (light == Material.TORCH) {
            consume(Material.TORCH, 1);
        } else {
            consume(light, 1);
        }
        carryCount = Math.max(0, carryCount - 1);
        setState(VillagerState.IDLE);
    }

    private void gatherComponent(HearthPlugin plugin, Village v, Material output, Material source) {
        Location src = findNearestSourceBlock(v, source, 48);
        if (src == null) {
            setState(VillagerState.IDLE);
            return;
        }
        carryMaterial = output;
        currentJob = new BuildJob(src.clone(), source, BuildJob.Kind.MINE);
        currentTask = TaskType.GATHER;
        startTravel(plugin, adjacentStandable(src), OnArrive.WORK);
    }

    private void workGather(HearthPlugin plugin, Village v) {
        Block src = currentJob.location.getBlock();
        Material sourceType = src.getType();
        if (sourceType == Material.AIR) {
            // Already mined (by a neighbor).
            setState(VillagerState.IDLE);
            return;
        }
        if (writesLeft <= 0 || readsLeft <= 0) {
            return;
        }
        writesLeft--;
        readsLeft--;

        // "Mine" the block: hand the expected item(s) to the villager.
        Material output = carryMaterial != null ? carryMaterial : sourceType;
        int units = (output == Material.STICK) ? 2 : 1;
        boolean canTake = canCarry(units);

        // Break the block on its own region thread (Folia); drop loot there too.
        plugin.runInRegion(src.getLocation(), () -> {
            if (src.getType() == Material.AIR) {
                return; // mined by a neighbor meanwhile
            }
            src.setType(Material.AIR);
            if (!canTake) {
                src.getWorld().dropItemNaturally(src.getLocation(), new ItemStack(output, units));
            }
            src.getWorld().playSound(src.getLocation(), Sound.BLOCK_STONE_BREAK, 0.5f, 0.9f);
        });
        if (canTake) {
            give(output, units);
            carryCount += units;
        }
        // If we are full, deliver; else keep working or idle.
        setState(VillagerState.IDLE); // doIdle will route to delivery
    }

    private void workMine(HearthPlugin plugin, Village v) {
        Block b = currentJob.location.getBlock();
        Material type = b.getType();
        if (type == Material.AIR) {
            advanceMine(v);
            setState(VillagerState.IDLE);
            return;
        }
        if (writesLeft <= 0 || readsLeft <= 0) {
            return;
        }
        writesLeft--;
        readsLeft--;

        // Break the block on its own region thread (Folia).
        Material drop = Drops.dropFor(type, rng);
        int amt = Drops.dropAmount(type);
        boolean carry = canCarry(amt);
        plugin.runInRegion(b.getLocation(), () -> {
            if (b.getType() == Material.AIR) {
                return; // mined by a neighbor meanwhile
            }
            b.setType(Material.AIR);
            if (!carry) {
                // Inventory full: drop the loot on the ground (villager will pick it up later).
                b.getWorld().dropItemNaturally(b.getLocation(), new ItemStack(drop, amt));
            }
            b.getWorld().playSound(b.getLocation(), Sound.BLOCK_STONE_BREAK, 0.5f, 0.9f);
        });
        if (carry) {
            give(drop, amt);
            carryCount += amt;
        }
        advanceMine(v);
        setState(VillagerState.IDLE);
    }

    private void advanceMine(Village v) {
        if (v.getMine() != null && currentJob != null && v.getMine().nextJob() == currentJob) {
            v.getMine().markDug(currentJob);
        }
    }

    // ---- Sleep / Flee ----

    private void startSleep(Village v, HearthPlugin plugin) {
        Location bed = nearestFreeBed(v);
        Location dest;
        if (bed != null) {
            // Walk to a standable spot right next to the bed; on arrival the
            // brain calls Villager#sleep(bed) which plays the vanilla sleep pose.
            pendingBed = bed.clone();
            dest = adjacentStandable(bed);
        } else {
            // No bed available: rest at the village center (still stops working).
            pendingBed = null;
            dest = homeSpot(v);
        }
        startTravel(plugin, dest, OnArrive.SLEEP);
    }

    private void startFlee(Village v, HearthPlugin plugin) {
        Location safe = v.getChestLocation() != null ? v.getChestLocation() : v.getCenter();
        startTravel(plugin, adjacentStandable(safe), OnArrive.FLEE_SAFE);
    }

    private Location nearestFreeBed(Village v) {
        Location best = null;
        double bestD = Double.MAX_VALUE;
        for (Location bed : v.getBeds()) {
            if (!bed.getWorld().equals(villager.getWorld())) {
                continue;
            }
            if (bedOccupied(bed)) {
                continue;
            }
            double d = bed.distanceSquared(villager.getLocation());
            if (d < bestD) {
                bestD = d;
                best = bed;
            }
        }
        return best;
    }

    /**
     * A bed is occupied if a sleeping villager is lying in it.
     */
    private boolean bedOccupied(Location bed) {
        for (Villager e : bed.getWorld().getEntitiesByClass(Villager.class)) {
            if (e.isSleeping() && e.getLocation().distanceSquared(bed) < 9.0) {
                return true;
            }
        }
        return false;
    }

    private Location homeSpot(Village v) {
        // A deterministic personal spot near the village center.
        int h = Math.abs(villager.getUniqueId().hashCode());
        double ang = (h % 360) * Math.PI / 180;
        int r = 4 + (h % 6);
        return new Location(v.getWorld(),
                v.getCenter().getBlockX() + Math.round(Math.cos(ang) * r),
                v.getCenter().getBlockY(),
                v.getCenter().getBlockZ() + Math.round(Math.sin(ang) * r));
    }

    // ---- Helpers ----

    private BuildJob nextMissingLightJob(Village v) {
        for (BuildJob job : v.getLightJobs()) {
            Material t = job.location.getBlock().getType();
            if (t == Material.AIR || BlockUtils.isReplaceable(t)) {
                return job;
            }
        }
        return null;
    }

    private BuildJob nextMissingWallJob(Village v) {
        List<BuildJob> jobs = v.getWallJobs();
        for (BuildJob job : jobs) {
            Material t = job.location.getBlock().getType();
            if (t == Material.AIR || BlockUtils.isReplaceable(t)) {
                return job;
            }
        }
        return null;
    }

    private BuildJob nextWallJobNear(Village v, double radius, Material material) {
        double r2 = radius * radius;
        for (BuildJob job : v.getWallJobs()) {
            if (job.material != material) {
                continue;
            }
            if (job.location.distanceSquared(villager.getLocation()) > r2) {
                continue;
            }
            Material t = job.location.getBlock().getType();
            if (t == Material.AIR || BlockUtils.isReplaceable(t)) {
                return job;
            }
        }
        return null;
    }

    /**
     * Find a standable neighbor of the given block location (to stand next to it).
     */
    private Location adjacentStandable(Location block) {
        World w = block.getWorld();
        int x = block.getBlockX();
        int y = block.getBlockY();
        int z = block.getBlockZ();
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};
        for (int i = 0; i < 4; i++) {
            if (BlockUtils.isStandable(w, x + dx[i], y, z + dz[i])) {
                return new Location(w, x + dx[i], y, z + dz[i]);
            }
        }
        // Fall back: the block location itself.
        return block.clone();
    }

    private Location findNearestSourceBlock(Village v, Material source, int maxDist) {
        World w = v.getWorld();
        Location c = v.getCenter();
        // Sample a growing ring of columns.
        for (int ring = 4; ring <= maxDist; ring += 4) {
            int angles = 8;
            for (int a = 0; a < angles; a++) {
                double rad = 2 * Math.PI * a / angles;
                int x = c.getBlockX() + (int) Math.round(Math.cos(rad) * ring);
                int z = c.getBlockZ() + (int) Math.round(Math.sin(rad) * ring);
                int topY;
                try {
                    topY = w.getHighestBlockYAt(x, z);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                for (int y = Math.max(w.getMinHeight(), topY - 6); y <= topY + 2; y++) {
                    if (w.getBlockAt(x, y, z).getType() == source) {
                        return new Location(w, x, y, z);
                    }
                }
            }
        }
        return null;
    }

    private Location findOreNearTunnel(HearthPlugin plugin, Village v) {
        MinePlanner.Mine mine = v.getMine();
        if (mine == null || mine.entrance == null) {
            return null;
        }
        World w = v.getWorld();
        // Scan a window around the tunnel entrance for ore blocks.
        int ex = mine.entrance.getBlockX();
        int ez = mine.entrance.getBlockZ();
        int depth = plugin.mineDepth();
        for (int dx = -6; dx <= 6; dx += 2) {
            for (int dz = -6; dz <= 6; dz += 2) {
                for (int y = depth; y <= depth + 6; y++) {
                    Material t = w.getBlockAt(ex + dx, y, ez + dz).getType();
                    if (Drops.isOre(t)) {
                        return new Location(w, ex + dx, y, ez + dz);
                    }
                }
            }
        }
        return null;
    }

    // ---- Inventory helpers ----

    private int invCount(Material m) {
        int n = 0;
        for (ItemStack item : villager.getInventory().getContents()) {
            if (item != null && item.getType() == m) {
                n += item.getAmount();
            }
        }
        return n;
    }

    private int invTotal() {
        int n = 0;
        for (ItemStack item : villager.getInventory().getContents()) {
            if (item != null) {
                n += item.getAmount();
            }
        }
        return n;
    }

    private boolean canCarry(int n) {
        return invTotal() + n <= 48;
    }

    private void give(Material m, int n) {
        villager.getInventory().addItem(new ItemStack(m, n));
    }

    private void consume(Material m, int n) {
        int left = n;
        for (ItemStack item : villager.getInventory().getContents()) {
            if (left <= 0) {
                break;
            }
            if (item != null && item.getType() == m) {
                int take = Math.min(left, item.getAmount());
                item.setAmount(item.getAmount() - take);
                left -= take;
            }
        }
    }

    private void depositToChest() {
        Village v = this.village;
        if (v == null || v.getChestLocation() == null) {
            carryCount = 0;
            carryMaterial = null;
            return;
        }
        Location chestLoc = v.getChestLocation();

        // 1. Remove the goods from the villager's inventory. This runs on the
        //    villager's own region thread — always the correct region for this
        //    entity (Folia).
        List<ItemStack> toDeposit = new ArrayList<>();
        for (int i = 0; i < villager.getInventory().getSize(); i++) {
            ItemStack item = villager.getInventory().getItem(i);
            if (item == null || item.getAmount() <= 0) {
                continue;
            }
            toDeposit.add(item.clone());
            villager.getInventory().setItem(i, null);
        }
        if (toDeposit.isEmpty()) {
            carryCount = 0;
            carryMaterial = null;
            return;
        }

        HearthPlugin plugin = HearthPlugin.get();
        if (plugin == null) {
            // Plugin disabling: drop the goods where the villager stands.
            for (ItemStack item : toDeposit) {
                villager.getWorld().dropItemNaturally(villager.getLocation(), item);
            }
            carryCount = 0;
            carryMaterial = null;
            return;
        }

        // 2. Add them to the chest on the chest's region thread (Folia). Any
        //    overflow that no longer fits is dropped next to the chest, so
        //    nothing is ever lost.
        plugin.runInRegion(chestLoc, () -> {
            if (!(chestLoc.getBlock().getState() instanceof org.bukkit.block.Chest chest)) {
                for (ItemStack item : toDeposit) {
                    chestLoc.getWorld().dropItemNaturally(chestLoc, item);
                }
                return;
            }
            org.bukkit.inventory.Inventory inv = chest.getBlockInventory();
            for (ItemStack item : toDeposit) {
                java.util.Map<Integer, ItemStack> overflow = inv.addItem(item);
                for (ItemStack rest : overflow.values()) {
                    chestLoc.getWorld().dropItemNaturally(chestLoc, rest);
                }
            }
            chestLoc.getWorld().playSound(chestLoc, Sound.ENTITY_ITEM_PICKUP, 0.3f, 1.4f);
        });
        carryCount = 0;
        carryMaterial = null;
    }

    // ---- Status ----

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("state=").append(state);
        if (currentTask != TaskType.IDLE) {
            sb.append(" task=").append(currentTask);
        }
        if (carryCount > 0) {
            sb.append(" carrying=").append(carryCount).append(' ').append(carryMaterial);
        }
        if (target != null) {
            sb.append(" target=").append(target.getBlockX()).append(',').append(target.getBlockY()).append(',').append(target.getBlockZ());
        }
        return sb.toString();
    }
}
