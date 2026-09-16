package dev.civilizations;

import dev.civilizations.ai.*;
import dev.civilizations.core.*;
import dev.civilizations.design.*;
import dev.civilizations.learning.*;
import dev.civilizations.world.*;
import java.util.*;
import java.util.Comparator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class CivilizationsPlugin extends JavaPlugin
    implements Listener, CommandExecutor, TabCompleter {
  private final VillageConnections connections = new VillageConnections();

  public VillageConnections connections() {
    return connections;
  }

  private int connectionDistance, connectionHeight;
  private final Map<String, Integer> surveyArea = new ConcurrentHashMap<>();
  private final Map<String, Map<Pos, Map<String, List<Pos>>>> areaResources =
      new ConcurrentHashMap<>();
  private final Map<String, Map<Pos, List<Pos>>> areaBeds = new ConcurrentHashMap<>();
  private final Map<String, Settlement> settlements = new ConcurrentHashMap<>();
  private final Map<String, VillagerWorker> workers = new ConcurrentHashMap<>();
  private final Map<String, Map<String, List<Pos>>> resources = new ConcurrentHashMap<>();
  private final Set<String> scanning = ConcurrentHashMap.newKeySet();
  private final Map<String, Long> scannedAt = new ConcurrentHashMap<>();
  private final ScheduledExecutorService persistence =
      Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "Civilizations-state"));
  private final ExecutorService planning =
      Executors.newFixedThreadPool(
          2,
          r -> {
            Thread t = new Thread(r, "Civilizations-planning");
            t.setDaemon(true);
            return t;
          });
  private final AtomicBoolean loaded = new AtomicBoolean();
  private volatile boolean closing;
  private StateStore store;
  private DebugJournal journal;
  private DebugJournal failures;
  private DebugJournal probes;
  private NavigationService navigation;

  public NavigationService navigation() {
    return navigation;
  }

  private CraftingBook recipes;

  public CraftingBook recipes() {
    return recipes;
  }

  public VillagerWorker worker(String id) {
    return workers.get(id);
  }

  public void debug(String village, String worker, String type, Map<String, ?> data) {
    if (journal != null) journal.event(village, worker, type, data);
    if (failures != null && dev.civilizations.core.FailureEvents.isFailure(type, data))
      failures.event(village, worker, type, data);
  }

  public void probeEvent(String village, String worker, String type, Map<String, ?> data) {
    if (probes != null) probes.event(village, worker, type, data);
  }

  private RegionSnapshots snapshots;
  private StorageObserver storageObserver;
  private LoadedVillagerDiscovery discovery;
  private LocalRuntime model;
  private InferenceQueue inference;
  private CoreAiCoordinator coreAi;
  private RecoveryExperiments experiments;

  public RecoveryExperiments experiments() {
    return experiments;
  }

  public CoreAiCoordinator coreAi() {
    return coreAi;
  }

  private final VillageThinkingBudget villageThinking = new VillageThinkingBudget();
  private LocalRuntime deepModel;
  private boolean routineDecisions;
  private DesignCoordinator designs;
  private boolean adaptiveDesign;
  private NamespacedKey villageKey, chestKey;
  private int maxWorkers, maxVillages, initialRadius, scanRadius;
  private long thinkMillis, workMillis;
  private double speed;
  private boolean autoDiscover;
  private boolean thinkingWhenStuck;
  private long reasoningCooldown;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    recipes = ServerRecipes.capture();
    probes =
        new DebugJournal(
            getDataFolder().toPath().resolve("debug/probes"),
            4L * 1024 * 1024,
            getLogger()::warning);
    failures =
        new DebugJournal(
            getDataFolder().toPath().resolve("debug/failures"),
            8L * 1024 * 1024,
            getLogger()::warning);
    getLogger().info("Loaded " + recipes.size() + " supported server crafting recipes");
    if (getConfig().getBoolean("debug.enabled", true))
      journal =
          new DebugJournal(
              getDataFolder().toPath().resolve("debug"),
              1024L * 1024 * Math.clamp(getConfig().getInt("debug.file-megabytes", 4), 1, 32),
              getLogger()::warning);
    maxWorkers = Math.clamp(getConfig().getInt("villagers.per-village", 20), 1, 32);
    connectionDistance = Math.clamp(getConfig().getInt("village.connection-distance", 48), 16, 64);
    connectionHeight = Math.clamp(getConfig().getInt("village.connection-height", 16), 4, 24);
    autoDiscover = getConfig().getBoolean("villagers.auto-discover", true);
    routineDecisions = getConfig().getBoolean("ai.routine-decisions", false);
    thinkingWhenStuck = getConfig().getBoolean("ai.thinking-when-stuck", true);
    reasoningCooldown =
        1000L * Math.clamp(getConfig().getInt("ai.recovery-cooldown-seconds", 120), 30, 900);
    maxVillages = Math.clamp(getConfig().getInt("villagers.max-villages", 4), 1, 32);
    initialRadius = Math.clamp(getConfig().getInt("village.initial-radius", 12), 8, 24);
    scanRadius = Math.clamp(getConfig().getInt("village.scan-radius", 48), 24, 64);
    thinkMillis =
        1000L * Math.clamp(getConfig().getInt("villagers.think-interval-seconds", 30), 10, 600);
    workMillis = 50L * Math.clamp(getConfig().getInt("villagers.work-interval-ticks", 20), 5, 200);
    speed = Math.clamp(getConfig().getDouble("villagers.movement-speed", 0.6), 0.2, 0.8);
    villageKey = new NamespacedKey(this, "settlement");
    chestKey = new NamespacedKey(this, "community_chest");
    store = new StateStore(getDataFolder().toPath().resolve("settlements"));
    snapshots = new RegionSnapshots(this, planning);
    navigation =
        new NavigationService(
            snapshots,
            planning,
            new dev.civilizations.navigation.NavigationArchive(
                getDataFolder().toPath().resolve("debug/navigation/maps"), getLogger()::warning),
            getConfig().getInt("navigation.search-radius", 20),
            getConfig().getBoolean("navigation.clear-natural-obstacles", true));
    storageObserver = new StorageObserver(this);
    model =
        new LocalRuntime(
            LocalRuntime.Settings.read(getConfig()),
            getDataFolder().toPath().resolve("local-ai"),
            s -> getLogger().info(s));
    if (getConfig().getBoolean("ai.enabled", true)
        && getConfig().getBoolean("ai.deep-thinking.enabled", true)) {
      deepModel =
          new LocalRuntime(
              LocalRuntime.Settings.read(getConfig(), "ai.deep-thinking"),
              getDataFolder().toPath().resolve("deep-ai"),
              s -> getLogger().info("Deep thinker: " + s));
    }
    ThinkingModels router =
        new ThinkingModels(model, deepModel, (stage, event) -> debug("", "", stage, event));
    inference =
        new InferenceQueue(router, Math.clamp(getConfig().getInt("ai.queue-capacity", 32), 1, 128));
    inference.observe((agent, event) -> debug("", agent, "inference", event));
    if (getConfig().getBoolean("core-ai.enabled", true)) {
      var primaryTeacher = TeacherProvenance.read(getConfig(), "ai");
      var deepTeacher = TeacherProvenance.read(getConfig(), "ai.deep-thinking");
      try {
        coreAi =
            new CoreAiCoordinator(
                getDataFolder().toPath().resolve("CoreAI"),
                inference,
                () -> router.lastWasDeep() ? deepTeacher : primaryTeacher,
                getLogger()::warning,
                getConfig().getBoolean("core-ai.adapt", true),
                1000L * Math.clamp(getConfig().getInt("core-ai.interval-seconds", 600), 60, 3600));
        coreAi.liveTrials(getConfig().getBoolean("core-ai.live-trials.enabled", true));
        if (getConfig().getBoolean("core-ai.adapt", true)
            && getConfig().getBoolean("core-ai.live-skills.enabled", true))
          experiments =
              new RecoveryExperiments(
                  getDataFolder().toPath().resolve("CoreAI"),
                  inference,
                  () ->
                      new com.google.gson.Gson()
                          .toJson(router.lastWasDeep() ? deepTeacher : primaryTeacher),
                  getLogger()::warning);
        if (experiments != null) {
          navigation.rules(experiments.rules());
          snapshots.rules(experiments.rules());
        }
        coreAi.saveKnowledge(Map.of("server", Bukkit.getVersion(), "recipes", recipes.snapshot()));
      } catch (java.io.IOException error) {
        getLogger()
            .warning(
                "CoreAI unavailable; built-in villager execution continues: " + error.getMessage());
      }
    }
    adaptiveDesign = getConfig().getBoolean("village.design.enabled", true);
    designs =
        new DesignCoordinator(
            inference,
            connections,
            snapshots,
            planning,
            () -> List.copyOf(settlements.values()),
            getDataFolder().toPath().resolve("designs"),
            1000L
                * Math.clamp(getConfig().getInt("village.design.interval-seconds", 180), 60, 1800),
            Math.clamp(getConfig().getInt("village.design.max-active-projects", 2), 1, 6),
            message -> getLogger().info(message));
    designs.observe((v, event) -> debug(v, "", "design", event));
    getServer().getPluginManager().registerEvents(this, this);
    Objects.requireNonNull(getCommand("civilizations")).setExecutor(this);
    getCommand("civilizations").setTabCompleter(this);
    persistence.execute(
        () -> {
          try {
            for (Settlement v : store.load(s -> getLogger().severe(s))) settlements.put(v.id(), v);
            connections.change(
                () -> {
                  consolidate();
                  return null;
                });
            loaded.set(true);
          } catch (Exception e) {
            getLogger()
                .severe(
                    "State load failed; discovery disabled to avoid replacing saved settlements: "
                        + e);
          }
        });
    persistence.scheduleWithFixedDelay(this::save, 10, 10, TimeUnit.SECONDS);
    discovery =
        new LoadedVillagerDiscovery(
            this,
            Math.clamp(getConfig().getInt("villagers.discovery-chunks-per-tick", 16), 1, 128),
            this::discover);
    discovery.start();
    Bukkit.getGlobalRegionScheduler()
        .runAtFixedRate(
            this,
            t -> {
              if (!loaded.get() || closing) return;
              discovery.refresh();
              for (Settlement v : settlements.values()) {
                World world = Bukkit.getWorld(UUID.fromString(v.world()));
                if (world != null) scan(v, world, false);
              }
            },
            20,
            200);
    Bukkit.getGlobalRegionScheduler()
        .runAtFixedRate(
            this,
            t -> {
              if (!loaded.get() || closing) return;
              for (Settlement v : settlements.values()) {
                World w = Bukkit.getWorld(UUID.fromString(v.world()));
                if (w != null) storageObserver.refresh(v, w);
              }
            },
            1,
            100);
    model.start();
    if (deepModel != null) deepModel.start();
    getLogger()
        .info(
            "Civilizations 2 enabled. Connected village discovery; "
                + maxWorkers
                + " workers per village"
                + " configured limit. Local AI downloads asynchronously.");
  }

  @Override
  public void onDisable() {
    closing = true;
    if (discovery != null) discovery.close();
    workers.values().forEach(VillagerWorker::stop);
    workers.clear();
    if (designs != null) designs.close();
    if (experiments != null) experiments.close();
    if (coreAi != null) coreAi.close();
    if (inference != null) inference.close();
    planning.shutdownNow();
    if (navigation != null) navigation.close();
    if (failures != null) failures.close();
    if (probes != null) probes.close();
    // Serialize final save behind earlier writes; no wait on any game thread.
    List<Settlement.Data> finalState =
        settlements.values().stream().map(Settlement::snapshot).toList();
    persistence.execute(
        () -> {
          try {
            if (loaded.get()) store.save(finalState);
          } catch (Exception e) {
            getLogger().severe("Final save failed: " + e);
          }
        });
    persistence.shutdown();
    if (journal != null) journal.close();
  }

  private void save() {
    if (!loaded.get() || closing) return;
    try {
      List<Settlement.Data> state = new ArrayList<>();
      connections.read(() -> settlements.values().forEach(v -> state.add(v.snapshot())));
      store.save(state);
    } catch (Exception e) {
      getLogger().severe("State save failed: " + e);
    }
  }

  public InferenceQueue inference() {
    return inference;
  }

  public boolean routineDecisions() {
    return routineDecisions;
  }

  public boolean takeRecoveryTurn(String village, long now) {
    return modelReady() && villageThinking.take(village, now, reasoningCooldown);
  }

  public boolean modelReady() {
    return model != null && model.ready() || deepModel != null && deepModel.ready();
  }

  public boolean thinkingWhenStuck() {
    return thinkingWhenStuck;
  }

  public long reasoningCooldown() {
    return reasoningCooldown;
  }

  public long decisionWaitMillis(boolean recovery) {
    if (recovery && deepModel != null) return 185_000;
    boolean thinking = recovery || getConfig().getBoolean("ai.thinking", false);
    return 1000L
        * (Math.clamp(
                getConfig()
                    .getInt(
                        thinking ? "ai.thinking-timeout-seconds" : "ai.timeout-seconds",
                        thinking ? 60 : 45),
                5,
                180)
            + 5);
  }

  public void requestPlan(Settlement village, World world) {
    scan(village, world, false);
  }

  public long thinkMillis() {
    return thinkMillis;
  }

  public long workMillis() {
    return workMillis;
  }

  public double speed() {
    return speed;
  }

  public void retired(String id, VillagerWorker worker) {
    workers.remove(id, worker);
  }

  public List<Pos> resources(String village, String material) {
    return resources.getOrDefault(village, Map.of()).getOrDefault(material, List.of());
  }

  public void exhausted(String village, Pos p) {
    resources.computeIfPresent(
        village,
        (id, map) -> {
          Map<String, List<Pos>> next = new HashMap<>();
          map.forEach((m, list) -> next.put(m, list.stream().filter(q -> !q.equals(p)).toList()));
          return Map.copyOf(next);
        });
  }

  public boolean playerProtected(Settlement v, Pos p) {
    return v.playerProtected(p);
  }

  public boolean mayChange(Villager actor, Block block, String action) {
    Pos p = pos(block.getLocation());
    Settlement v = forMember(actor.getUniqueId().toString());
    if (v == null || v.playerProtected(p)) {
      debug(
          v == null ? "" : v.id(),
          actor.getUniqueId().toString(),
          "work_permission_failure",
          Map.of(
              "action",
              action,
              "target",
              p,
              "material",
              block.getType().name(),
              "reason",
              v == null ? "worker_not_enrolled" : "recorded_player_placement"));
      return false;
    }
    WorkEvent event = new WorkEvent(actor, block, action);
    Bukkit.getPluginManager().callEvent(event);
    if (event.isCancelled())
      debug(
          v.id(),
          actor.getUniqueId().toString(),
          "work_permission_failure",
          Map.of(
              "action",
              action,
              "target",
              p,
              "material",
              block.getType().name(),
              "reason",
              "work_event_cancelled",
              "registered_listener_plugins",
              java.util.Arrays.stream(event.getHandlers().getRegisteredListeners())
                  .map(listener -> listener.getPlugin().getName())
                  .distinct()
                  .toList()));
    return !event.isCancelled();
  }

  @EventHandler
  public void onLoad(EntitiesLoadEvent event) {
    for (Entity entity : event.getEntities())
      if (entity instanceof Villager v) v.getScheduler().run(this, t -> discover(v), () -> {});
  }

  @EventHandler(ignoreCancelled = true)
  public void onSpawn(CreatureSpawnEvent event) {
    if (event.getEntity() instanceof Villager v)
      v.getScheduler().runDelayed(this, t -> discover(v), () -> {}, 20);
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void onWorkerDamage(EntityDamageEvent event) {
    if (event.getEntity() instanceof Villager actor && event.getFinalDamage() > 0) {
      VillagerWorker worker = workers.get(actor.getUniqueId().toString());
      if (worker != null) worker.damaged(event.getCause().name());
    }
  }

  @EventHandler
  public void onDeath(EntityDeathEvent event) {
    connections.read(
        () -> {
          if (event.getEntity() instanceof Villager v) {
            String id = v.getUniqueId().toString();
            Settlement village = forMember(id);
            if (village != null) village.remove(id);
            VillagerWorker w = workers.remove(id);
            if (w != null) {
              debug(
                  village == null ? "" : village.id(),
                  id,
                  "worker_death_failure",
                  Map.of(
                      "cause",
                      v.getLastDamageCause() == null
                          ? "unknown"
                          : v.getLastDamageCause().getCause().name(),
                      "inspection",
                      w.inspection(),
                      "last_navigation",
                      w.navigationEvidence()));
            }
            if (w != null) w.stop();
          }
        });
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void onPlace(BlockPlaceEvent event) {
    connections.read(
        () -> {
          Pos p = pos(event.getBlock().getLocation());
          String world = event.getBlock().getWorld().getUID().toString();
          for (Settlement v : settlements.values())
            if (v.world().equals(world)
                && v.areas().stream()
                    .anyMatch(a -> a.horizontal2(p) < (long) scanRadius * scanRadius))
              v.playerPlaced(p);
        });
  }

  private void attachSaved(Villager entity) {
    if (!loaded.get() || closing || !entity.isAdult()) return;
    String saved = entity.getPersistentDataContainer().get(villageKey, PersistentDataType.STRING);
    Settlement village =
        saved == null ? forMember(entity.getUniqueId().toString()) : settlements.get(saved);
    if (village == null) village = forMember(entity.getUniqueId().toString());
    if (village != null) attach(entity, village);
  }

  private void discover(Villager entity) {
    connections.change(
        () -> {
          discoverConnected(entity);
          return null;
        });
  }

  private void discoverConnected(Villager entity) {
    if (!loaded.get() || closing || !entity.isValid() || entity.isDead() || !entity.isAdult())
      return;
    String id = entity.getUniqueId().toString();
    VillagerWorker current = workers.get(id);
    if (current != null && current.retiredVillage()) {
      current.stop();
      workers.remove(id, current);
    }
    attachSaved(entity);
    Settlement v = forMember(id);
    Pos at = pos(entity.getLocation());
    if (v != null) {
      v.position(id, at);
      if (autoDiscover) consolidate();
      return;
    }
    if (!autoDiscover) return;
    v = connectedTo(entity.getWorld(), at);
    if (v == null) v = create(entity.getWorld(), at);
    if (v != null) {
      attach(entity, v);
      v.position(id, at);
      consolidate();
    }
  }

  /** Called only by the entity scheduler after a previous village has been combined. */
  public void reconnect(Villager entity) {
    discover(entity);
  }

  private Settlement connectedTo(World world, Pos p) {
    return settlements.values().stream()
        .filter(v -> v.world().equals(world.getUID().toString()))
        .filter(
            v ->
                v.connectionPoints().stream()
                    .anyMatch(
                        q -> VillageConnections.near(p, q, connectionDistance, connectionHeight)))
        .min(Comparator.comparingLong(v -> v.center().distance2(p)))
        .orElse(null);
  }

  private Settlement create(World world, Pos center) {
    return connections.change(
        () -> {
          Settlement existing = connectedTo(world, center);
          if (existing != null) return existing;
          if (settlements.size() >= maxVillages) return null;
          Settlement.Data d = new Settlement.Data();
          d.world = world.getUID().toString();
          d.center = center;
          d.radius = initialRadius;
          Settlement v = new Settlement(d);
          settlements.put(v.id(), v);
          getLogger().info("Created village " + v.id() + " at " + center.key());
          scan(v, world, true);
          return v;
        });
  }

  /** The write gate excludes work/plan/storage callbacks while ownership is moved. */
  private void consolidate() {
    boolean changed;
    do {
      changed = false;
      List<Settlement> all =
          settlements.values().stream().sorted(Comparator.comparing(Settlement::id)).toList();
      outer:
      for (int i = 0; i < all.size(); i++)
        for (int j = i + 1; j < all.size(); j++) {
          Settlement a = all.get(i), b = all.get(j);
          if (!VillageConnections.connected(a, b, connectionDistance, connectionHeight)) continue;
          Settlement merged = VillageConnections.combine(List.of(a, b));
          a.retire();
          b.retire();
          settlements.remove(a.id());
          settlements.remove(b.id());
          settlements.put(merged.id(), merged);
          scannedAt.remove(merged.id());
          areaResources.remove(merged.id());
          areaBeds.remove(merged.id());
          Map<String, List<Pos>> combined = new HashMap<>();
          for (Settlement old : List.of(a, b))
            resources
                .getOrDefault(old.id(), Map.of())
                .forEach((m, ps) -> combined.computeIfAbsent(m, k -> new ArrayList<>()).addAll(ps));
          combined.replaceAll((m, ps) -> ps.stream().distinct().toList());
          resources.put(merged.id(), Map.copyOf(combined));
          debug(
              merged.id(),
              "",
              "village_merge",
              Map.of(
                  "sources",
                  List.of(a.id(), b.id()),
                  "members",
                  merged.population(),
                  "chests",
                  merged.chests(),
                  "jobs",
                  merged.jobs().size()));
          getLogger()
              .info(
                  "Combined nearby villages "
                      + a.id()
                      + " + "
                      + b.id()
                      + " -> "
                      + merged.id()
                      + "; villagers="
                      + merged.population()
                      + "; community chests="
                      + merged.chests().size());
          changed = true;
          break outer;
        }
    } while (changed);
  }

  private void attach(Villager entity, Settlement v) {
    connections.change(
        () -> {
          attachCurrent(entity, v);
          return null;
        });
  }

  private void attachCurrent(Villager entity, Settlement v) {
    String id = entity.getUniqueId().toString();
    if (!entity.getWorld().getUID().toString().equals(v.world())
        || v.retired()
        || workers.containsKey(id)
        || !v.enroll(id, maxWorkers)) return;
    entity.getPersistentDataContainer().set(villageKey, PersistentDataType.STRING, v.id());
    VillagerWorker worker = new VillagerWorker(this, entity, v);
    if (workers.putIfAbsent(id, worker) == null) worker.start();
  }

  private Settlement forMember(String id) {
    for (Settlement v : settlements.values()) if (v.members().contains(id)) return v;
    return null;
  }

  private Settlement nearest(World world, Pos p, int radius) {
    return settlements.values().stream()
        .filter(
            v ->
                v.world().equals(world.getUID().toString())
                    && v.areas().stream().anyMatch(a -> a.horizontal2(p) < (long) radius * radius))
        .min(Comparator.comparingLong(v -> v.center().distance2(p)))
        .orElse(null);
  }

  private void scan(Settlement v, World world, boolean force) {
    long now = System.currentTimeMillis();
    if (closing
        || v.paused()
        || v.retired()
        || !force
            && now - scannedAt.getOrDefault(v.id(), 0L) < 60_000 / Math.max(1, v.areas().size())
        || !scanning.add(v.id())) return;
    scannedAt.put(v.id(), now);
    int radius = v.radius();
    List<Pos> areas = v.areas();
    Pos survey =
        areas.get(Math.floorMod(surveyArea.merge(v.id(), 1, Integer::sum) - 1, areas.size()));
    // Capture configuration before crossing into planning workers.
    int height = Math.clamp(getConfig().getInt("village.wall-height", 3), 2, 3),
        depth = Math.clamp(getConfig().getInt("village.mine-depth", 12), 3, 16),
        length = Math.clamp(getConfig().getInt("village.mine-length", 12), 3, 16);
    boolean expand = getConfig().getBoolean("village.auto-expand", true),
        lights = getConfig().getBoolean("village.lights", true);
    int maxRadius = Math.clamp(getConfig().getInt("village.maximum-radius", 24), initialRadius, 28);
    snapshots
        .capture(world, survey, scanRadius)
        .thenAcceptAsync(
            terrain ->
                connections.read(
                    () -> {
                      if (closing || v.retired()) return;
                      Planner.Result plan =
                          new Planner()
                              .plan(
                                  terrain,
                                  survey,
                                  radius,
                                  height,
                                  depth,
                                  length,
                                  p ->
                                      !v.playerProtected(p)
                                          && !v.protectedPos(p)
                                          && settlements.values().stream()
                                              .noneMatch(
                                                  other ->
                                                      !other.id().equals(v.id())
                                                          && other.world().equals(v.world())
                                                          && other.population() > 0
                                                          && other.chests().contains(p)),
                                  !adaptiveDesign);
                      Map<String, List<Pos>> sources = new HashMap<>();
                      sources.put("COBBLESTONE", plan.stone());
                      sources.put("COAL", plan.coal());
                      sources.put("WHEAT_SEEDS", plan.seeds());
                      sources.put("SAND", plan.sand());
                      sources.put("RED_SAND", plan.redSand());
                      debug(
                          v.id(),
                          "",
                          "survey_coverage",
                          Map.of("origin", survey, "coverage", terrain.observationReport()));
                      sources.put("LOG", plan.logs());
                      for (Pos log : plan.logs())
                        sources.computeIfAbsent(terrain.type(log), k -> new ArrayList<>()).add(log);
                      var local =
                          areaResources.computeIfAbsent(v.id(), k -> new ConcurrentHashMap<>());
                      local.put(survey, Map.copyOf(sources));
                      Map<String, List<Pos>> shared = new HashMap<>();
                      local
                          .values()
                          .forEach(
                              map ->
                                  map.forEach(
                                      (m, ps) ->
                                          shared
                                              .computeIfAbsent(m, k -> new ArrayList<>())
                                              .addAll(ps)));
                      shared.replaceAll((m, ps) -> ps.stream().distinct().toList());
                      resources.put(v.id(), Map.copyOf(shared));
                      Set<Pos> knownBeds = new HashSet<>(plan.beds());
                      // An unloaded/timed-out snapshot is not evidence that a saved bed
                      // disappeared.
                      for (Pos bed : v.beds())
                        if (terrain.type(bed).equals("UNKNOWN")) knownBeds.add(bed);
                      for (Job j : v.jobs())
                        if (j.complete
                            && j.material.equals("WHITE_BED")
                            && terrain.type(j.target).equals("WHITE_BED")) knownBeds.add(j.target);
                      var observedBeds =
                          areaBeds.computeIfAbsent(v.id(), k -> new ConcurrentHashMap<>());
                      observedBeds.put(survey, List.copyOf(knownBeds));
                      observedBeds.values().forEach(knownBeds::addAll);
                      v.beds(List.copyOf(knownBeds));
                      if (getConfig().getBoolean("village.repair-existing-structures", true)) {
                        var repairReport = VillageRepairs.survey(v, terrain);
                        debug(v.id(), "", "repair_survey", repairReport);
                      }
                      if (v.chest() == null
                          && plan.chest() != null
                          && terrain.type(plan.chest()).equals("CHEST"))
                        placeChest(v, world, plan.chest());
                      else if (StoragePlanning.schedule(v, terrain, survey, scanRadius, now))
                        debug(
                            v.id(),
                            "",
                            "storage_project",
                            Map.of(
                                "reason",
                                v.chest() == null
                                    ? "initial_shared_storage"
                                    : "observed_full_shared_storage",
                                "origin",
                                survey));
                      if (!survey.equals(v.center())) return;
                      if (!adaptiveDesign) {
                        v.addProject("wall-" + radius, plan.wall());
                        v.addProject("mine", plan.mine());
                        // Reserve the complete house before lighting; claim() waits for its wall
                        // and lower
                        // courses.
                        v.addProject("house", plan.house());
                        if (lights)
                          v.addProject(
                              "lights",
                              plan.lights().stream()
                                  .filter(j -> !v.protectedPos(j.target))
                                  .toList());
                        List<Job> farm =
                            new FarmPlanner()
                                .plan(terrain, v.center()).stream()
                                    .filter(
                                        j ->
                                            !v.gatherProtected(j.target)
                                                && !v.protectedPos(j.target.add(0, -1, 0))
                                                && !v.playerProtected(j.target)
                                                && !v.playerProtected(j.target.add(0, -1, 0)))
                                    .toList();
                        v.addProject("farm", farm);
                        List<String> constraints = new ArrayList<>();
                        if (v.chest() == null && plan.chest() == null)
                          constraints.add(
                              "No safe chest site found within 16 blocks; carry materials and"
                                  + " request a new scan");
                        if (!v.hasProject("wall-" + radius) && plan.wall().isEmpty())
                          constraints.add(
                              "Wall blocked: full perimeter crosses unavailable terrain, water,"
                                  + " structures, or steep slopes");
                        if (!v.hasProject("mine") && plan.mine().isEmpty())
                          constraints.add(
                              "Mine blocked: no continuous dry natural staircase/gallery found");
                        if (!v.hasProject("farm") && farm.isEmpty())
                          constraints.add(
                              "Wheat farm blocked: requires clear soil with nearby existing water");
                        if (plan.coal().isEmpty() && v.stock().getOrDefault("COAL", 0) == 0)
                          constraints.add(
                              "No exposed local coal observed; torches need coal, not cobblestone");
                        v.needs().planning(constraints);
                      } else {
                        List<String> constraints = new ArrayList<>();
                        if (v.chest() == null)
                          constraints.add(
                              "Community chest not yet available; retain task materials");
                        if (plan.coal().isEmpty() && v.stock().getOrDefault("COAL", 0) == 0)
                          constraints.add("No exposed local coal observed; lighting needs fuel");
                        constraints.add(
                            "Architect maps terrain and proposes validated designs; unsafe sites"
                                + " must be revised");
                        v.needs().planning(constraints);
                      }
                      for (Job j : v.jobs())
                        if (j.kind == Job.Kind.FARM
                            && j.complete
                            && terrain.available(j.target.x(), j.target.z())
                            && (terrain.matureWheat(j.target) || terrain.clear(j.target)))
                          v.damaged(j.id);
                      for (Job j : v.jobs())
                        if (j.complete
                            && j.everBuilt
                            && (j.kind == Job.Kind.PLACE || j.kind == Job.Kind.PATH)
                            && terrain.available(j.target.x(), j.target.z())) {
                          if (WorkState.damaged(j, terrain)) v.damaged(j.id);
                        }
                      if (!adaptiveDesign
                          && expand
                          && v.allComplete("wall-" + radius)
                          && v.allComplete("house")
                          && radius + 4 <= maxRadius) {
                        List<Job> outer =
                            new Planner().wall(terrain, v.center(), radius + 4, height);
                        if (v.addProject("wall-" + (radius + 4), outer)) {
                          v.radius(radius + 4);
                          getLogger()
                              .info(
                                  "Settlement "
                                      + v.id()
                                      + " expanding wall to radius "
                                      + (radius + 4));
                        }
                      }
                      if (adaptiveDesign)
                        designs.consider(
                            v,
                            world,
                            terrain,
                            Map.of(
                                "COAL",
                                plan.coal().size(),
                                "COBBLESTONE",
                                plan.stone().size(),
                                "LOG",
                                plan.logs().size(),
                                "WHEAT_SEEDS",
                                plan.seeds().size()));
                    }),
            planning)
        .whenComplete(
            (ignored, error) -> {
              scanning.remove(v.id());
              if (error != null && !closing)
                getLogger().warning("Planning failed: " + error.getMessage());
            });
  }

  public void placeChest(Settlement v, World world, Pos pos) {
    Bukkit.getRegionScheduler()
        .execute(
            this,
            location(world, pos),
            () ->
                connections.read(
                    () -> {
                      if (closing || v.retired()) return;
                      if (!Bukkit.isOwnedByCurrentRegion(location(world, pos), 1)) return;
                      Block b = location(world, pos).getBlock();
                      if (v.playerProtected(pos) || b.getType() != Material.CHEST) return;
                      if (b.getState() instanceof Chest chest) {
                        String owner =
                            chest
                                .getPersistentDataContainer()
                                .get(chestKey, PersistentDataType.STRING);
                        if (owner != null && !owner.equals(v.id())) {
                          Settlement previous = settlements.get(owner);
                          if (previous != null && previous.population() > 0) return;
                          if (previous != null) previous.chest(null);
                        }
                        chest
                            .getPersistentDataContainer()
                            .set(chestKey, PersistentDataType.STRING, v.id());
                        chest.update();
                        v.chest(pos);
                        v.storageCapacity().fulfilled();
                        getLogger()
                            .info("Community chest ready for " + v.id() + " at " + pos.key());
                      }
                    }));
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    boolean detailed = args.length > 0 && args[0].equalsIgnoreCase("debug");
    if (detailed) {
      if (args.length == 1) {
        sender.sendMessage(
            "Optional diagnostics: /civ debug details | probe | ai | design | coreai | plan |"
                + " create");
        sender.sendMessage(
            "Villagers discover, plan and work automatically; these commands are not setup steps.");
        return true;
      }
      args = Arrays.copyOfRange(args, 1, args.length);
    }
    String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
    if (detailed && sub.equals("details")) sub = "status";
    if (sub.equals("probe")) {
      ProbeCommand.execute(this, sender, args);
      return true;
    }
    if (sub.equals("help")) {
      sender.sendMessage(
          "/civ: village progress | /civ inspect: nearest villager | /civ pause or resume");
      sender.sendMessage("/civ debug: optional diagnostics. Routine village work is automatic.");
      return true;
    }
    if (sub.equals("status")) {
      sender.sendMessage(
          "Civilizations 2 | settlements="
              + settlements.size()
              + " active villagers="
              + workers.size()
              + " state="
              + (loaded.get() ? "loaded" : "loading"));
      if (detailed) {
        sender.sendMessage("AI: " + inference.status());
        sender.sendMessage(coreAi == null ? "CoreAI disabled" : coreAi.status());
        if (experiments != null) sender.sendMessage("Live skills: " + experiments.status());
        sender.sendMessage(
            "Observed structure blocks available for repair: "
                + settlements.values().stream().mapToInt(v -> v.repairBlocks().size()).sum());
        sender.sendMessage(
            "Discovery: loaded chunks; auto-enroll="
                + autoDiscover
                + "; pending chunks="
                + discovery.pendingChunks());
      }
      for (Settlement v : settlements.values()) {
        List<Job> jobs = v.jobs();
        sender.sendMessage(
            v.id().substring(0, 8)
                + " | "
                + v.population()
                + " villagers | jobs="
                + jobs.stream().filter(j -> j.complete).count()
                + "/"
                + jobs.size()
                + " | "
                + (v.paused() ? "paused" : "active"));
        if (detailed)
          sender.sendMessage(
              "  shared chests=" + v.chests() + "; " + String.join("; ", v.needs().constraints()));
        else {
          var activeDesigns =
              v.designs().stream()
                  .filter(d -> !v.allComplete(d.project()))
                  .collect(
                      java.util.stream.Collectors.groupingBy(
                          DesignRecord::kind, java.util.stream.Collectors.counting()));
          sender.sendMessage("  Projects underway: " + activeDesigns);
          sender.sendMessage("  Planning: " + designs.status(v));
        }
      }
      if (detailed)
        workers
            .values()
            .forEach(w -> sender.sendMessage(w.id().substring(0, 8) + ": " + w.status()));
      else {
        var activity =
            workers.values().stream()
                .collect(
                    java.util.stream.Collectors.groupingBy(
                        w -> w.status().split(" ", 2)[0], java.util.stream.Collectors.counting()));
        sender.sendMessage("Villager activity: " + activity);
        sender.sendMessage(
            "Work is automatic. /civ inspect explains a nearby villager; /civ debug shows optional"
                + " diagnostics.");
      }
      return true;
    }
    if (sub.equals("design")) {
      sender.sendMessage("Adaptive village design: " + (adaptiveDesign ? "enabled" : "disabled"));
      for (Settlement v : settlements.values()) {
        sender.sendMessage(v.id().substring(0, 8) + ": " + designs.status(v));
        v.designs().stream()
            .skip(Math.max(0, v.designs().size() - 3))
            .forEach(
                d ->
                    sender.sendMessage(
                        "  "
                            + d.project()
                            + " | "
                            + (v.allComplete(d.project()) ? "complete" : "building")
                            + " | "
                            + d.jobs()
                            + " actions | materials "
                            + d.materials()));
      }
      sender.sendMessage(
          "Latest terrain maps and accepted layouts: plugins/Civilizations/designs/");
      return true;
    }
    if (sub.equals("coreai")) {
      if (coreAi == null) sender.sendMessage("CoreAI disabled");
      else if (args.length > 1 && args[1].equalsIgnoreCase("rollback")) {
        coreAi.rollback();
        sender.sendMessage("CoreAI rollback queued; check /civ coreai for completion.");
      } else sender.sendMessage(coreAi.status());
      return true;
    }
    if (sub.equals("ai")) {
      sender.sendMessage(inference.status());
      return true;
    }
    if (!(sender instanceof Player player)) {
      sender.sendMessage("Use this command in game near a village.");
      return true;
    }
    // Commands run on the player's owning region; nearby entities are checked before access.
    if (!loaded.get()) {
      sender.sendMessage("Saved settlements are still loading.");
      return true;
    }
    if (sub.equals("inspect")) {
      if (!Bukkit.isOwnedByCurrentRegion(player.getLocation(), 1)) {
        sender.sendMessage("Nearby region is not available for inspection.");
        return true;
      }
      Villager nearby =
          player.getNearbyEntities(16, 8, 16).stream()
              .filter(
                  e ->
                      e instanceof Villager
                          && Bukkit.isOwnedByCurrentRegion(e)
                          && workers.containsKey(e.getUniqueId().toString()))
              .map(e -> (Villager) e)
              .min(
                  Comparator.comparingDouble(
                      e -> e.getLocation().distanceSquared(player.getLocation())))
              .orElse(null);
      if (nearby == null) sender.sendMessage("No controlled villager within 16 blocks.");
      else
        connections.read(
            () ->
                workers
                    .get(nearby.getUniqueId().toString())
                    .inspection()
                    .forEach(sender::sendMessage));
      return true;
    }
    if (sub.equals("create")) {
      if (!Bukkit.isOwnedByCurrentRegion(player.getLocation(), 2)) {
        sender.sendMessage("Nearby regions are busy; try again shortly.");
        return true;
      }
      List<Villager> nearby =
          player.getNearbyEntities(24, 12, 24).stream()
              .filter(e -> e instanceof Villager && Bukkit.isOwnedByCurrentRegion(e))
              .map(e -> (Villager) e)
              .filter(Villager::isAdult)
              .sorted(
                  Comparator.comparingDouble(
                      v -> v.getLocation().distanceSquared(player.getLocation())))
              .toList();
      if (nearby.isEmpty()) {
        sender.sendMessage(
            "Bring adult villagers nearby first. Civilizations adopts existing villagers.");
        return true;
      }
      Settlement v = create(player.getWorld(), pos(nearby.getFirst().getLocation()));
      if (v == null) {
        sender.sendMessage("Maximum settlements reached.");
        return true;
      }
      for (Villager entity : nearby) {
        Settlement old = forMember(entity.getUniqueId().toString());
        if (old == null || old == v) attach(entity, v);
      }
      sender.sendMessage(
          "Settlement "
              + v.id().substring(0, 8)
              + ": "
              + v.population()
              + " villagers enrolled. Planning terrain and installing local AI in the background.");
      return true;
    }
    Settlement v = nearest(player.getWorld(), pos(player.getLocation()), 96);
    if (v == null) {
      sender.sendMessage("No settlement nearby. Use /civ create near adult villagers.");
      return true;
    }
    switch (sub) {
      case "pause" -> {
        v.paused(true);
        sender.sendMessage("Settlement paused.");
      }
      case "resume" -> {
        v.paused(false);
        sender.sendMessage("Settlement resumed.");
      }
      case "plan" -> {
        scan(v, player.getWorld(), true);
        sender.sendMessage("Terrain scan requested. Unloaded or unsafe sites are deferred.");
      }
      default -> sender.sendMessage("/civ | /civ inspect | /civ pause | /civ resume | /civ debug");
    }
    return true;
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    List<String> choices =
        args.length == 1
            ? List.of("status", "inspect", "pause", "resume", "debug", "help")
            : args.length == 2 && args[0].equalsIgnoreCase("debug")
                ? List.of("details", "probe", "ai", "design", "coreai", "plan", "create")
                : args.length == 3
                        && args[0].equalsIgnoreCase("debug")
                        && args[1].equalsIgnoreCase("probe")
                    ? List.of("start", "status", "cancel", "jobs")
                    : List.of();
    return choices.stream()
        .filter(s -> s.startsWith(args[args.length - 1].toLowerCase(Locale.ROOT)))
        .toList();
  }

  private static Pos pos(Location l) {
    return new Pos(l.getBlockX(), l.getBlockY(), l.getBlockZ());
  }

  private static Location location(World w, Pos p) {
    return new Location(w, p.x(), p.y(), p.z());
  }
}
