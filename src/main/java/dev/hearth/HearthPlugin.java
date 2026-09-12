package dev.hearth;

import dev.hearth.ai.AIAdvisor;
import dev.hearth.ai.AIAdvice;
import dev.hearth.ai.AgentPool;
import dev.hearth.ai.LocalAIManager;
import dev.hearth.ai.VillagerAgent;
import dev.hearth.brain.PriorityPolicy;
import dev.hearth.brain.TaskType;
import dev.hearth.brain.VillagerBrain;
import dev.hearth.build.BuildJob;
import dev.hearth.build.LightPlanner;
import dev.hearth.build.MinePlanner;
import dev.hearth.build.WallPlanner;
import dev.hearth.move.MovementController;
import dev.hearth.path.PathStore;
import dev.hearth.village.ChestManager;
import dev.hearth.village.Village;
import dev.hearth.village.VillageManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hearth - AI-assisted villager collective.
 *
 * <p>Architecture:
 * <ul>
 *   <li>{@link VillageManager} detects and owns villages.</li>
 *   <li>{@link VillagerBrain} is the per-villager state machine (travel, work, carry, sleep, flee).</li>
 *   <li>{@link PathStore} runs budgeted, resumable A* pathfinding (Baritone-style ideas, original implementation).</li>
 *   <li>{@link MovementController} moves villagers at normal walking speed.</li>
 *   <li>Planners ({@link WallPlanner}, {@link MinePlanner}, {@link LightPlanner}) produce concrete build jobs.</li>
 *   <li>The Quen AI core (optional): a pool of mini-agents ({@code dev.hearth.ai})
 *       that run on their own threads against either a bundled local Qwen/llama.cpp
 *       runtime or a remote OpenAI-compatible API; local rules in
 *       {@link PriorityPolicy} keep it safe when AI is off or fails.</li>
 * </ul>
 *
 * <p><b>Folia threading model (genuinely region-safe):</b>
 * <ul>
 *   <li>Each villager's brain ticks on the <em>villager's own entity scheduler</em>
 *       ({@code Entity#getScheduler}), i.e. always on the region thread that owns the
 *       villager; Folia keeps the task attached when the entity crosses regions. All
 *       entity operations (inventory, teleport, velocity, sleep) happen there.</li>
 *   <li>Each village's coordination (stats + task resolution) runs on a
 *       <em>region task bound to the village center</em> — the single writer of
 *       village-level state.</li>
 *   <li>Discovery (rescan + starting tasks) runs on the <em>global region</em> thread
 *       and only <em>reads</em> the world; it never writes blocks.</li>
 *   <li>Block writes that are not guaranteed to be in the current region (community
 *       chest placement, chest inventory transfers, block placement/breaking) are
 *       routed via {@link #runInRegion} to the owning region thread, with the state
 *       re-validated there.</li>
 *   <li>The only off-thread work is the LLM HTTP call; its result is marshalled back
 *       to the village center's region before touching village state.</li>
 * </ul>
 * No task is ever scheduled on the legacy global {@code BukkitScheduler} for world
 * or entity access, which is what Folia forbids.
 */
public class HearthPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static HearthPlugin instance;

    // Config
    private volatile boolean enabled = true;
    private double maxSpeed = 0.22;
    private boolean stepAssist = true;
    private double stepAssistDistance = 2.2;
    private boolean faceDirection = true;
    private int pathBudgetPerTick = 6000;
    private int maxPathRadius = 96;
    private int maxPathDepth = 56000;
    private int blockReadsPerVillager = 24;
    private int blockWritesPerVillager = 6;
    private int villageMinSize = 1;
    private int villageRadius = 48;
    private int rescanInterval = 600;
    private boolean chestAutoPlace = true;
    private int chestMaxDistance = 24;
    private int chestMaxCarry = 16;
    private boolean wallsAutoBuild = true;
    private Material wallMaterial = Material.STONE;
    private int wallHeight = 4;
    private boolean wallGate = true;
    private Material gateMaterial = Material.OAK_DOOR;
    private double wallRepairBelow = 0.8;
    private Material lightMaterial = Material.GLOWSTONE;
    private boolean lightWallRing = true;
    private boolean lightChest = true;
    private boolean lightMine = true;
    private int lightMinLevel = 8;
    private boolean miningEnabled = true;
    private int mineDepth = -40;
    private int mineLength = 64;
    private int mineWidth = 3;
    private int minePillarEvery = 4;
    private boolean avoidWater = true;
    private boolean avoidLava = true;
    private boolean avoidHouse = true;
    private int mineRouteAttempts = 6;
    private boolean sleepEnabled = true;
    private long sleepFrom = 17000L;
    private long sleepUntil = 7000L;
    private int fleeRadius = 8;
    private volatile boolean aiEnabled = false;
    /** local = self-hosted Quen runtime (downloaded into local-ai/); external = remote OpenAI-compatible API. */
    private String aiBackend = "local";
    private int aiAgentsCount = 3;
    private String aiCharter = "Hearth is a peaceful collective.";
    private double aiTemperature = 0.2;
    private int aiMaxTokens = 220;
    private int aiTimeoutSeconds = 25;
    private int aiIntervalMinutes = 4;
    // External (remote) OpenAI-compatible endpoint.
    private String aiExternalBaseUrl = "https://api.deepseek.com/v1";
    private String aiExternalModel = "deepseek-chat";
    private String aiExternalApiKey = "";
    // Local Quen runtime (llama.cpp + Qwen GGUF).
    private int aiLocalPort = 8642;
    private int aiLocalContext = 4096;
    private int aiLocalThreads = 4;
    private String aiLocalModelRepo = "Qwen/Qwen2.5-1.5B-Instruct-GGUF";
    private String aiLocalModelFile = "qwen2.5-1.5b-instruct-q4_k_m.gguf";

    // Runtime
    private VillageManager villageManager;
    private PathStore pathStore;
    private MovementController movement;
    private ChestManager chestManager;
    private WallPlanner wallPlanner;
    private MinePlanner minePlanner;
    private LightPlanner lightPlanner;
    private PriorityPolicy priorityPolicy;
    private AIAdvisor aiAdvisor;
    private LocalAIManager localAI;
    private AgentPool agentPool;
    private final Map<UUID, VillagerBrain> brains = new ConcurrentHashMap<>();
    /** Villagers whose brain task is currently running (one entity task each). */
    private final Set<UUID> startedBrains = ConcurrentHashMap.newKeySet();
    private NamespacedKey chestKey;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadConfig();
        chestKey = new NamespacedKey(this, "chest_village");

        villageManager = new VillageManager(this);
        pathStore = new PathStore(this);
        movement = new MovementController(this);
        chestManager = new ChestManager(this);
        wallPlanner = new WallPlanner(this);
        minePlanner = new MinePlanner(this);
        lightPlanner = new LightPlanner(this);
        localAI = new LocalAIManager(this);
        agentPool = new AgentPool(this);
        agentPool.refresh();
        aiAdvisor = agentPool;
        priorityPolicy = new PriorityPolicy(this);

        getServer().getPluginManager().registerEvents(this, this);
        org.bukkit.command.PluginCommand cmd = getCommand("hearth");
        if (cmd != null) {
            cmd.setTabCompleter(this);
            cmd.setExecutor(this);
        }

        // Folia: no legacy global-scheduler tasks for world/entity access.
        // Discovery runs on the global region thread (reads only) and starts the
        // per-villager (entity) and per-village (region) tasks.
        scheduleDiscovery();

        getLogger().info("Hearth enabled (Folia region-threaded). Villagers will build walls, dig dry mines, light up, gather, and sleep at night.");
        if (aiEnabled) {
            if ("local".equalsIgnoreCase(aiBackend)) {
                getLogger().info("Quen local AI: bootstrapping into local-ai/ (first run downloads the llama.cpp runtime + Qwen model, ~1.1 GB).");
                localAI.start();
            } else {
                getLogger().info("Quen AI advisor: external endpoint " + aiExternalBaseUrl + " / " + aiExternalModel);
            }
        }
    }

    @Override
    public void onDisable() {
        // Stop the Quen agents first (no more LLM calls), then the local runtime.
        if (agentPool != null) {
            agentPool.shutdown();
        }
        if (localAI != null) {
            localAI.shutdown();
        }
        // Folia cancels all of the plugin's region/entity tasks on disable.
        // Best-effort: wake anyone asleep so they are not stuck in the bed pose on
        // restart (a short teleport breaks the sleep animation). During shutdown the
        // region threads may already be gone, so this is wrapped defensively.
        for (VillagerBrain brain : brains.values()) {
            try {
                Villager v = brain.getVillager();
                if (v != null && v.isValid() && v.isSleeping()) {
                    Location here = v.getLocation();
                    v.teleport(here.clone().add(0.01, 0.01, 0.01));
                }
            } catch (Throwable ignored) {
                // Server shutting down; the sleep pose clears when the world reloads.
            }
        }
        brains.clear();
        if (instance == this) {
            instance = null;
        }
    }

    // -------------------------------------------------------------
    // Folia region scheduling
    // -------------------------------------------------------------

    /**
     * Folia: run a block/world <em>write</em> on the region thread that owns
     * {@code loc}. If the current thread already owns that region the task runs
     * immediately; otherwise it is queued onto the owning region. Block and
     * entity <em>reads</em> are legal from any thread, so only writes (and the
     * sound/drop side effects that accompany them) are routed this way.
     */
    public void runInRegion(Location loc, Runnable task) {
        try {
            Bukkit.getRegionScheduler().execute(this, loc, task);
        } catch (Throwable t) {
            // Defensive fallback for single-threaded (non-Folia) servers, where
            // there is no region ownership to begin with: run inline.
            task.run();
        }
    }

    /**
     * Global-region discovery: finds villages + villagers (reads only) and starts
     * the region-bound tasks for them. Runs once shortly after enable, then on a
     * slow interval — it also picks up villagers that spawn between rescans
     * (spawn events start their brain immediately, see
     * {@link #onVillagerSpawn(EntitySpawnEvent)}).
     */
    private void scheduleDiscovery() {
        long period = Math.max(20L, rescanInterval);
        Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> discoverAndStart(), 20L);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, t -> discoverAndStart(), period, period);
    }

    private void discoverAndStart() {
        if (!isEnabled() || !enabled) {
            return;
        }
        // 1. (Re)detect villages. World access here is read-only; the one chest
        //    placement it may trigger is region-routed by ChestManager.
        try {
            villageManager.rescan();
        } catch (Throwable t) {
            getLogger().warning("Village rescan failed: " + t);
        }

        // 2. One coordination task per village, bound to the village center's region.
        for (Village village : villageManager.getVillages()) {
            startVillageTask(village);
        }

        // 3. One brain task per villager, bound to the villager's entity.
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e instanceof Villager v && !v.isDead()) {
                    startBrain(v);
                }
            }
        }
    }

    /**
     * Start (once) the per-villager brain task. It runs on the villager's own
     * entity scheduler — always the region thread that owns the villager — and
     * Folia keeps it attached when the entity moves between regions.
     */
    public void startBrain(Villager v) {
        if (v == null || v.isDead() || !v.isValid()) {
            return;
        }
        UUID id = v.getUniqueId();
        if (!startedBrains.add(id)) {
            return; // brain already running
        }
        VillagerBrain brain = brainFor(v);
        try {
            // EntityScheduler.runAtFixedRate(plugin, onScheduled, body, delay, period)
            ScheduledTask task = v.getScheduler().runAtFixedRate(this, t -> { }, () -> tickVillager(v, brain), 0L, 1L);
            brain.setTask(task);
        } catch (Throwable t) {
            startedBrains.remove(id);
            getLogger().warning("Could not start brain for " + v.getUniqueId() + ": " + t);
        }
    }

    /**
     * One tick of one villager's brain, on the villager's own region thread.
     */
    private void tickVillager(Villager v, VillagerBrain brain) {
        if (!isEnabled() || v.isDead() || !v.isValid()) {
            cancelBrain(v);
            return;
        }
        if (!enabled) {
            return; // paused via /hearth stop
        }
        try {
            brain.tick(this, System.currentTimeMillis());
            PathStore.Request req = brain.getPathRequest();
            if (req != null) {
                pathStore.tickFor(this, req);
            }
            movement.stepFor(v, brain);
        } catch (Throwable t) {
            getLogger().warning("Brain tick failed for " + v.getUniqueId() + ": " + t);
        }
    }

    /**
     * Stop and forget a villager's brain (villager died / plugin disabled).
     */
    public void cancelBrain(Villager v) {
        UUID id = v.getUniqueId();
        if (startedBrains.remove(id)) {
            VillagerBrain brain = brains.get(id);
            if (brain != null && brain.getTask() != null) {
                try {
                    brain.getTask().cancel();
                } catch (Throwable ignored) {
                    // already cancelled
                }
            }
            brains.remove(id);
        }
    }

    /**
     * Start (once) the village coordination task, bound to the village center's
     * region thread. It is the single writer of village-level state (stats,
     * wall/mine/light plans, task resolution).
     */
    public void startVillageTask(Village village) {
        if (village == null || village.isTaskScheduled()) {
            return;
        }
        Location center = village.getCenter();
        try {
            // RegionScheduler.runAtFixedRate(plugin, location, task, delay, period)
            ScheduledTask task = Bukkit.getRegionScheduler().runAtFixedRate(this, center, t -> {
                if (isEnabled() && enabled) {
                    try {
                        village.tick(this);
                    } catch (Throwable ex) {
                        getLogger().warning("Village tick failed for " + village.getName() + ": " + ex);
                    }
                }
            }, 0L, 1L);
            village.setVillageTask(task);
            village.setTaskScheduled(true);
        } catch (Throwable t) {
            getLogger().warning("Could not start village task for " + village.getName() + ": " + t);
        }
    }

    /**
     * Folia-safe chest → villager transfer: the chest inventory write runs on the
     * chest's region thread, and the villager inventory write is queued onto the
     * villager's own region thread. The brain waits a tick and re-checks its
     * inventory (see {@code transferPending} in the brain).
     */
    public void transferFromChest(Location chest, Material material, int n, VillagerBrain brain) {
        if (chest == null || brain == null || n <= 0) {
            return;
        }
        runInRegion(chest, () -> {
            if (!(chest.getBlock().getState() instanceof org.bukkit.block.Chest c)) {
                return;
            }
            org.bukkit.inventory.Inventory inv = c.getBlockInventory();
            int taken = 0;
            outer:
            for (int i = 0; i < inv.getSize(); i++) {
                if (taken >= n) {
                    break outer;
                }
                ItemStack item = inv.getItem(i);
                if (item == null || item.getType() != material) {
                    continue;
                }
                int take = Math.min(n - taken, item.getAmount());
                if (take >= item.getAmount()) {
                    inv.setItem(i, null);
                } else {
                    item.setAmount(item.getAmount() - take);
                }
                taken += take;
            }
            if (taken <= 0) {
                return;
            }
            Villager v = brain.getVillager();
            if (v == null || !v.isValid() || v.isDead()) {
                chest.getWorld().dropItemNaturally(chest, new ItemStack(material, taken));
                return;
            }
            final int toGive = taken; // effectively-final capture for the entity-region lambda
            v.getScheduler().run(this, t -> { }, () -> {
                if (!v.isValid() || v.isDead()) {
                    v.getWorld().dropItemNaturally(v.getLocation(), new ItemStack(material, toGive));
                    return;
                }
                Map<Integer, ItemStack> overflow = v.getInventory().addItem(new ItemStack(material, toGive));
                for (ItemStack rest : overflow.values()) {
                    v.getWorld().dropItemNaturally(v.getLocation(), rest);
                }
            });
        });
    }

    public VillagerBrain brainFor(Villager v) {
        return brains.computeIfAbsent(v.getUniqueId(), id -> new VillagerBrain(v));
    }

    public VillagerBrain brainsOf(UUID id) {
        return brains.get(id);
    }

    // -------------------------------------------------------------
    // Config loading
    // -------------------------------------------------------------

    private void loadConfig() {
        var c = getConfig();
        enabled = c.getBoolean("settings.enabled", true);
        maxSpeed = c.getDouble("settings.movement.max-speed", 0.22);
        stepAssist = c.getBoolean("settings.movement.step-assist", true);
        stepAssistDistance = c.getDouble("settings.movement.step-assist-distance", 2.2);
        faceDirection = c.getBoolean("settings.movement.face-direction", true);
        pathBudgetPerTick = c.getInt("settings.performance.pathfind-budget-per-tick", 6000);
        maxPathRadius = c.getInt("settings.performance.max-path-radius", 96);
        maxPathDepth = c.getInt("settings.performance.max-path-depth", 56000);
        blockReadsPerVillager = c.getInt("settings.performance.block-reads-per-villager-per-tick", 24);
        blockWritesPerVillager = c.getInt("settings.performance.block-writes-per-villager-per-tick", 6);
        villageMinSize = c.getInt("villages.min-size", 1);
        villageRadius = c.getInt("villages.radius", 48);
        rescanInterval = c.getInt("villages.rescan-interval", 600);
        chestAutoPlace = c.getBoolean("chest.auto-place", true);
        chestMaxDistance = c.getInt("chest.max-distance", 24);
        chestMaxCarry = c.getInt("chest.max-carry", 16);
        wallsAutoBuild = c.getBoolean("walls.auto-build", true);
        wallMaterial = parseMaterial(c.getString("walls.material", "STONE"), Material.STONE);
        wallHeight = c.getInt("walls.height", 4);
        wallGate = c.getBoolean("walls.gate", true);
        gateMaterial = parseMaterial(c.getString("walls.gate-material", "OAK_DOOR"), Material.OAK_DOOR);
        wallRepairBelow = c.getDouble("walls.repair-below", 0.8);
        lightMaterial = parseMaterial(c.getString("lighting.material", "GLOWSTONE"), Material.GLOWSTONE);
        lightWallRing = c.getBoolean("lighting.wall-ring", true);
        lightChest = c.getBoolean("lighting.chest-area", true);
        lightMine = c.getBoolean("lighting.mine", true);
        lightMinLevel = c.getInt("lighting.min-light-level", 8);
        miningEnabled = c.getBoolean("mining.enabled", true);
        mineDepth = c.getInt("mining.depth", -40);
        mineLength = c.getInt("mining.length", 64);
        mineWidth = c.getInt("mining.width", 3);
        minePillarEvery = c.getInt("mining.pillar-every", 4);
        avoidWater = c.getBoolean("mining.avoid-water", true);
        avoidLava = c.getBoolean("mining.avoid-lava", true);
        avoidHouse = c.getBoolean("mining.avoid-house-blocks", true);
        mineRouteAttempts = c.getInt("mining.max-route-attempts", 6);
        sleepEnabled = c.getBoolean("sleep.enabled", true);
        sleepFrom = c.getLong("sleep.sleep-from", 17000L);
        sleepUntil = c.getLong("sleep.sleep-until", 7000L);
        fleeRadius = c.getInt("defense.flee-radius", 8);
        aiEnabled = c.getBoolean("ai.enabled", false);
        aiBackend = c.getString("ai.backend", "local").toLowerCase(Locale.ROOT);
        aiAgentsCount = Math.max(1, c.getInt("ai.agents-count", 3));
        aiCharter = c.getString("ai.charter", "Hearth is a peaceful collective.");
        aiTemperature = c.getDouble("ai.temperature", 0.2);
        aiMaxTokens = c.getInt("ai.max-tokens", 220);
        aiTimeoutSeconds = c.getInt("ai.timeout-seconds", 25);
        aiIntervalMinutes = c.getInt("ai.interval-minutes", 4);
        // External endpoint; the v1.0.0 top-level keys (ai.base-url/ai.model/ai.api-key)
        // still work, so an existing config keeps functioning after the upgrade.
        aiExternalBaseUrl = firstNonBlank(c.getString("ai.external.base-url"), c.getString("ai.base-url"), "https://api.deepseek.com/v1");
        aiExternalModel = firstNonBlank(c.getString("ai.external.model"), c.getString("ai.model"), "deepseek-chat");
        aiExternalApiKey = firstNonBlank(c.getString("ai.external.api-key"), c.getString("ai.api-key"), "");
        aiLocalPort = c.getInt("ai.local.port", 8642);
        aiLocalContext = c.getInt("ai.local.context-size", 4096);
        aiLocalThreads = c.getInt("ai.local.threads", 4);
        aiLocalModelRepo = c.getString("ai.local.model-repo", "Qwen/Qwen2.5-1.5B-Instruct-GGUF");
        aiLocalModelFile = c.getString("ai.local.model-file", "qwen2.5-1.5b-instruct-q4_k_m.gguf");
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private Material parseMaterial(String name, Material fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material m = Material.matchMaterial(name.trim());
        return m != null ? m : fallback;
    }

    public void reloadPlugin() {
        reloadConfig();
        loadConfig();
        if (agentPool != null) {
            agentPool.refresh();
        }
        if (localAI != null && aiEnabled && "local".equalsIgnoreCase(aiBackend) && !localAI.ready()) {
            localAI.start();
        }
        getLogger().info("Hearth config reloaded.");
    }

    // -------------------------------------------------------------
    // Command handling
    // -------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> help(sender);
            case "list" -> listVillages(sender);
            case "status" -> status(sender, args);
            case "wall" -> forceTask(sender, args, TaskType.WALL, "wall");
            case "mine" -> forceTask(sender, args, TaskType.MINE, "mine");
            case "light" -> forceTask(sender, args, TaskType.LIGHT, "light");
            case "chest" -> chestCommand(sender);
            case "ai" -> aiCommand(sender, args);
            case "reload" -> {
                if (!sender.hasPermission("hearth.admin")) {
                    sender.sendMessage("§cNo permission.");
                    return true;
                }
                reloadPlugin();
                sender.sendMessage("§aHearth config reloaded.");
            }
            case "stop" -> {
                if (!sender.hasPermission("hearth.admin")) {
                    sender.sendMessage("§cNo permission.");
                    return true;
                }
                enabled = false;
                sender.sendMessage("§eHearth is now paused. Use /hearth reload to resume.");
            }
            default -> {
                sender.sendMessage("§cUnknown subcommand. Try /hearth help");
            }
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage("§6=== Hearth ===");
        s.sendMessage("/hearth list - list villages");
        s.sendMessage("/hearth status [villager] - village/brain status");
        s.sendMessage("/hearth wall|mine|light - force a task now");
        s.sendMessage("/hearth chest - show community chest info");
        s.sendMessage("/hearth ai on|off|test|status - Quen AI advisor (local Quen runtime or external API)");
        s.sendMessage("/hearth reload - reload config");
        s.sendMessage("/hearth stop - pause the plugin");
    }

    private void listVillages(CommandSender s) {
        List<Village> villages = villageManager.getVillages();
        if (villages.isEmpty()) {
            s.sendMessage("§eNo villages found yet. Spawn some villagers and try /hearth list again.");
            return;
        }
        for (Village v : villages) {
            s.sendMessage("§6" + v.getName() + "§7: " + v.getVillagers().size() + " villagers, "
                    + v.getVillagerUuids().size() + " tracked, wall " + Math.round(v.getWallIntegrity() * 100)
                    + "%, chest " + (v.getChestLocation() != null ? "yes" : "no")
                    + ", mine " + (v.getMine() != null ? (v.getMine().completed ? "done" : v.getMine().getProgressPercent() + "%") : "none"));
        }
    }

    private void status(CommandSender s, String[] args) {
        List<Village> villages = villageManager.getVillages();
        if (villages.isEmpty()) {
            s.sendMessage("§eNo villages found.");
            return;
        }
        for (Village v : villages) {
            s.sendMessage("§6" + v.getName() + "§7: " + v.getVillagers().size() + " villagers");
            s.sendMessage("  Wall: " + Math.round(v.getWallIntegrity() * 100) + "% integrity, " + v.getWallJobs().size() + " jobs");
            s.sendMessage("  Chest: " + (v.getChestLocation() != null ? v.getChestLocation().toString() : "none"));
            s.sendMessage("  Mine: " + (v.getMine() != null ? v.getMine().toString() : "none"));
            s.sendMessage("  Current task: " + v.getCurrentTask());
            s.sendMessage("  AI advice: " + (v.getLastAdvice() != null ? v.getLastAdvice().task + " (" + v.getLastAdvice().reason + ")" : "none"));
            for (Villager villager : v.getVillagers()) {
                VillagerBrain brain = brains.get(villager.getUniqueId());
                if (brain != null) {
                    s.sendMessage("    §7- " + villager.getName() + " §8[" + brain.getState() + "]§7: " + brain.describe());
                }
            }
        }
    }

    private void forceTask(CommandSender s, String[] args, TaskType task, String label) {
        Village v = villageManager.getVillages().isEmpty() ? null : villageManager.getVillages().get(0);
        if (v == null) {
            s.sendMessage("§eNo village found.");
            return;
        }
        v.setCurrentTask(task);
        v.setTaskForced(true);
        s.sendMessage("§aVillage §6" + v.getName() + "§a is now told to focus on §f" + label + "§a.");
    }

    private void chestCommand(CommandSender s) {
        Village v = villageManager.getVillages().isEmpty() ? null : villageManager.getVillages().get(0);
        if (v == null) {
            s.sendMessage("§eNo village found.");
            return;
        }
        if (v.getChestLocation() == null) {
            s.sendMessage("§eVillage §6" + v.getName() + "§e has no chest yet. Villagers will place one soon (or /hearth reload to re-enable).");
            return;
        }
        Location chest = v.getChestLocation();
        s.sendMessage("§6" + v.getName() + "§7: chest at " + chest.getBlockX() + ", " + chest.getBlockY() + ", " + chest.getBlockZ());
        org.bukkit.inventory.ItemStack[] items = chestManager.contents(v);
        int total = 0;
        for (org.bukkit.inventory.ItemStack item : items) {
            if (item != null) total += item.getAmount();
        }
        s.sendMessage("  Items stored: " + total);
    }

    private void aiCommand(CommandSender s, String[] args) {
        if (args.length < 2) {
            aiStatus(s);
            s.sendMessage("Usage: /hearth ai on|off|test|status");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on" -> {
                aiEnabled = true;
                if ("local".equalsIgnoreCase(aiBackend) && localAI != null && !localAI.ready()) {
                    localAI.start();
                    s.sendMessage("§aAI advisor enabled. §7Local Quen runtime is starting (see console); local rules decide until it reports READY.");
                } else {
                    s.sendMessage("§aAI advisor enabled.");
                }
            }
            case "off" -> {
                aiEnabled = false;
                s.sendMessage("§eAI advisor disabled. Local rules decide priorities.");
            }
            case "test" -> {
                Village v = villageManager.getVillages().isEmpty() ? null : villageManager.getVillages().get(0);
                if (v == null) {
                    s.sendMessage("§eNo village to test with.");
                    return;
                }
                s.sendMessage("§7Asking Quen for advice about §6" + v.getName() + "§7...");
                aiAdvisor.advise(this, v, advice -> {
                    s.sendMessage("§7Quen says: §f" + (advice != null ? advice.task + " - " + advice.reason : "null (local rules apply)"));
                });
            }
            case "status" -> aiStatus(s);
            default -> s.sendMessage("§cUsage: /hearth ai on|off|test|status");
        }
    }

    private void aiStatus(CommandSender s) {
        s.sendMessage("§6Quen AI advisor§7: " + (aiEnabled ? "§aON" : "§cOFF")
                + " | backend: " + aiBackend
                + ("local".equalsIgnoreCase(aiBackend)
                        ? (localAI != null ? " | local runtime: " + localAI.status() : "")
                        : " | endpoint: " + aiExternalBaseUrl + " / " + aiExternalModel));
        if (agentPool != null) {
            for (VillagerAgent agent : agentPool.agents()) {
                s.sendMessage("  §7" + agent.status());
            }
        }
    }

    // -------------------------------------------------------------
    // Tab completion
    // -------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(Arrays.asList("help", "list", "status", "wall", "mine", "light", "chest", "ai", "reload", "stop"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("ai")) {
            return filter(Arrays.asList("on", "off", "test", "status"), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix.toLowerCase(Locale.ROOT);
        for (String o : options) {
            if (o.startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }

    // -------------------------------------------------------------
    // Listeners
    // -------------------------------------------------------------

    @EventHandler
    public void onVillagerSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof Villager v)) {
            return;
        }
        // Keep villagers from despawning; they are the workforce.
        v.setPersistent(true);
        villageManager.onVillagerSeen(v);
        // Folia: the spawn event fires on the villager's own region thread, so it
        // is safe to start the entity-bound brain task here (immediately, without
        // waiting for the next discovery pass).
        if (enabled) {
            startBrain(v);
        }
    }

    // -------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------

    public static HearthPlugin get() {
        return instance;
    }

    /**
     * Runtime on/off switch (see /hearth stop). Named isRunning() because
     * JavaPlugin#isEnabled() is final and reports plugin lifecycle state.
     */
    public boolean isRunning() {
        return enabled;
    }

    public VillageManager villageManager() {
        return villageManager;
    }

    public PathStore pathStore() {
        return pathStore;
    }

    public MovementController movement() {
        return movement;
    }

    public ChestManager chestManager() {
        return chestManager;
    }

    public WallPlanner wallPlanner() {
        return wallPlanner;
    }

    public MinePlanner minePlanner() {
        return minePlanner;
    }

    public LightPlanner lightPlanner() {
        return lightPlanner;
    }

    public PriorityPolicy priorityPolicy() {
        return priorityPolicy;
    }

    public AIAdvisor aiAdvisor() {
        return aiAdvisor;
    }

    public NamespacedKey chestKey() {
        return chestKey;
    }

    // ---- config getters (used by brains/planners) ----

    public double maxSpeed() {
        return maxSpeed;
    }

    public boolean stepAssist() {
        return stepAssist;
    }

    public double stepAssistDistance() {
        return stepAssistDistance;
    }

    public boolean faceDirection() {
        return faceDirection;
    }

    public int pathBudgetPerTick() {
        return pathBudgetPerTick;
    }

    public int maxPathRadius() {
        return maxPathRadius;
    }

    public int maxPathDepth() {
        return maxPathDepth;
    }

    public int blockReadsPerVillager() {
        return blockReadsPerVillager;
    }

    public int blockWritesPerVillager() {
        return blockWritesPerVillager;
    }

    public int villageMinSize() {
        return villageMinSize;
    }

    public int villageRadius() {
        return villageRadius;
    }

    public int rescanInterval() {
        return rescanInterval;
    }

    public boolean chestAutoPlace() {
        return chestAutoPlace;
    }

    public int chestMaxDistance() {
        return chestMaxDistance;
    }

    public int chestMaxCarry() {
        return chestMaxCarry;
    }

    public boolean wallsAutoBuild() {
        return wallsAutoBuild;
    }

    public Material wallMaterial() {
        return wallMaterial;
    }

    public int wallHeight() {
        return wallHeight;
    }

    public boolean wallGate() {
        return wallGate;
    }

    public Material gateMaterial() {
        return gateMaterial;
    }

    public double wallRepairBelow() {
        return wallRepairBelow;
    }

    public Material lightMaterial() {
        return lightMaterial;
    }

    public boolean lightWallRing() {
        return lightWallRing;
    }

    public boolean lightChest() {
        return lightChest;
    }

    public boolean lightMine() {
        return lightMine;
    }

    public int lightMinLevel() {
        return lightMinLevel;
    }

    public boolean miningEnabled() {
        return miningEnabled;
    }

    public int mineDepth() {
        return mineDepth;
    }

    public int mineLength() {
        return mineLength;
    }

    public int mineWidth() {
        return mineWidth;
    }

    public int minePillarEvery() {
        return minePillarEvery;
    }

    public boolean avoidWater() {
        return avoidWater;
    }

    public boolean avoidLava() {
        return avoidLava;
    }

    public boolean avoidHouse() {
        return avoidHouse;
    }

    public int mineRouteAttempts() {
        return mineRouteAttempts;
    }

    public boolean sleepEnabled() {
        return sleepEnabled;
    }

    public long sleepFrom() {
        return sleepFrom;
    }

    public long sleepUntil() {
        return sleepUntil;
    }

    public int fleeRadius() {
        return fleeRadius;
    }

    public boolean aiEnabled() {
        return aiEnabled;
    }

    public void setAiEnabled(boolean b) {
        this.aiEnabled = b;
    }

    public String aiBackend() {
        return aiBackend;
    }

    public int aiAgentsCount() {
        return aiAgentsCount;
    }

    public String aiExternalBaseUrl() {
        return aiExternalBaseUrl;
    }

    public String aiExternalModel() {
        return aiExternalModel;
    }

    public String aiExternalApiKey() {
        return aiExternalApiKey;
    }

    public int aiLocalPort() {
        return aiLocalPort;
    }

    public int aiLocalContext() {
        return aiLocalContext;
    }

    public int aiLocalThreads() {
        return aiLocalThreads;
    }

    public String aiLocalModelRepo() {
        return aiLocalModelRepo;
    }

    public String aiLocalModelFile() {
        return aiLocalModelFile;
    }

    public LocalAIManager localAI() {
        return localAI;
    }

    public AgentPool agentPool() {
        return agentPool;
    }

    /**
     * Resolve the endpoint the Quen agents talk to, for the current backend.
     * Local backend -> the bundled llama-server (only valid while READY);
     * external backend -> the configured remote API.
     */
    public String aiEndpointBaseUrl() {
        if ("local".equalsIgnoreCase(aiBackend)) {
            return localAI != null && localAI.ready() ? localAI.baseUrl() : "";
        }
        return aiExternalBaseUrl;
    }

    public String aiEndpointModel() {
        if ("local".equalsIgnoreCase(aiBackend)) {
            return aiLocalModelFile;
        }
        return aiExternalModel;
    }

    public String aiEndpointApiKey() {
        if ("local".equalsIgnoreCase(aiBackend)) {
            return "quen"; // ignored by llama-server, but keeps the client simple
        }
        return aiExternalApiKey;
    }

    public double aiTemperature() {
        return aiTemperature;
    }

    public int aiMaxTokens() {
        return aiMaxTokens;
    }

    public int aiTimeoutSeconds() {
        return aiTimeoutSeconds;
    }

    public int aiIntervalMinutes() {
        return aiIntervalMinutes;
    }

    public String aiCharter() {
        return aiCharter;
    }

    // -------------------------------------------------------------
    // Small shared helpers
    // -------------------------------------------------------------

    /**
     * Find a hostile mob within {@code radius} of the given location, or null.
     */
    public static Monster nearestMonster(Location at, int radius) {
        Monster best = null;
        double bestD = radius * radius;
        for (Entity e : at.getWorld().getEntities()) {
            if (e instanceof Monster m && m.isValid() && !m.isDead()) {
                double d = m.getLocation().distanceSquared(at);
                if (d < bestD) {
                    bestD = d;
                    best = m;
                }
            }
        }
        return best;
    }

    /**
     * Count items of a given material in the chest at the given location.
     * Read-only, so it is safe to call from any region thread.
     */
    public static int chestCount(Material material, Location chest) {
        if (chest == null || !(chest.getBlock().getState() instanceof org.bukkit.block.Chest c)) {
            return 0;
        }
        int n = 0;
        for (ItemStack item : c.getBlockInventory()) {
            if (item != null && item.getType() == material) {
                n += item.getAmount();
            }
        }
        return n;
    }

    public static void log(HearthPlugin p, String msg) {
        if (p != null) {
            p.getLogger().info(msg);
        }
    }
}
