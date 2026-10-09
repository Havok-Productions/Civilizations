package test;

import com.google.gson.*;
import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.ai.*;
import dev.civilizations.core.*;
import dev.civilizations.learning.RecoveryExperiments;
import dev.civilizations.learning.RecoveryReplay;
import dev.civilizations.navigation.NavigationMap;
import dev.civilizations.world.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.bukkit.*;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;

/** Scripted physical prerequisite control, then persisted lesson reuse 128 blocks away, offline. */
final class SkillTransferChecks {
  private static final int Y = 64;
  private final String recordedPath =
      System.getProperty("civilizations.test.recorded-recovery", "");
  private JsonObject recorded;
  private NavigationMap recordedMap;
  private int recordedReach;
  private Map<String, Integer> recordedInventory = Map.of();
  private final boolean voxel = Boolean.getBoolean("civilizations.test.voxel-recovery");

  private int barrier(Pos origin) {
    return voxel && origin.x() != 0 ? 4 : 3;
  }

  private static final String PROGRAM =
      """
      {"explanation":"Deterministic fixture: obtain a real tool, mine the observed stone barrier and reach the retained goal.","steps":[
      {"op":"ACQUIRE","x":0,"y":0,"z":0,"material":"WOODEN_PICKAXE"},
      {"op":"MINE","x":3,"y":1,"z":0,"material":"STONE"},
      {"op":"MINE","x":3,"y":0,"z":0,"material":"STONE"},
      {"op":"VERIFY","x":0,"y":0,"z":0,"material":""}]}
      """;
  private final JavaPlugin fixture;
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicBoolean ending = new AtomicBoolean();
  private final Set<Pos> tickets = ConcurrentHashMap.newKeySet();
  private final List<Map<String, Object>> stages = new ArrayList<>();
  private CivilizationsPlugin plugin;
  private World world;
  private RecoveryExperiments original, service;
  private InferenceQueue queue;
  private Path evidence;
  private String firstPortableId;

  SkillTransferChecks(JavaPlugin fixture) {
    this.fixture = fixture;
  }

  void start() {
    Bukkit.getGlobalRegionScheduler()
        .runDelayed(
            fixture,
            task -> {
              world = Bukkit.getWorlds().getFirst();
              world.setTime(1000);
              plugin = (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
              original = plugin.experiments();
              evidence =
                  plugin
                      .getDataFolder()
                      .toPath()
                      .resolve("CoreAI-skill-transfer-" + world.getUID());
              load(0);
            },
            60);
  }

  private void load(int stage) {
    List<CompletableFuture<Void>> pending = new ArrayList<>();
    for (int x = stage * 8 - 3; x <= stage * 8 + 3; x++)
      for (int z = -3; z <= 3; z++) {
        int cx = x, cz = z;
        var ready = new CompletableFuture<Void>();
        pending.add(ready);
        world
            .getChunkAtAsync(cx, cz, true)
            .thenAccept(
                chunk ->
                    Bukkit.getRegionScheduler()
                        .execute(
                            fixture,
                            world,
                            cx,
                            cz,
                            () -> {
                              chunk.addPluginChunkTicket(fixture);
                              tickets.add(new Pos(cx, 0, cz));
                              ready.complete(null);
                            }))
            .exceptionally(
                error -> {
                  ready.completeExceptionally(error);
                  return null;
                });
      }
    CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
        .orTimeout(30, TimeUnit.SECONDS)
        .whenComplete(
            (ignored, error) -> {
              if (error != null) finish(error);
              else
                Bukkit.getRegionScheduler()
                    .runDelayed(
                        fixture, location(new Pos(stage * 128, Y, 0)), task -> seed(stage), 60);
            });
  }

  @SuppressWarnings("unchecked")
  private void seed(int stage) {
    try {
      Pos origin = new Pos(stage * 128, Y, 0), goal = origin.add(6, 0, 0);
      if (!recordedPath.isEmpty()) {
        recorded =
            JsonParser.parseString(Files.readString(Path.of(recordedPath))).getAsJsonObject();
        Pos source = new Gson().fromJson(recorded.get("origin"), Pos.class);
        Pos target = new Gson().fromJson(recorded.get("goal"), Pos.class);
        goal =
            origin.add(target.x() - source.x(), target.y() - source.y(), target.z() - source.z());
        recordedReach = recorded.get("reach2").getAsInt();
        recordedInventory = new HashMap<>();
        recorded
            .getAsJsonObject("inventory")
            .entrySet()
            .forEach(e -> recordedInventory.put(e.getKey(), e.getValue().getAsInt()));
        var decoded = RecoveryReplay.decode(recorded.getAsJsonObject("map").toString());
        var translated = new HashMap<Pos, NavigationMap.Cell>();
        for (var entry : decoded.cells().entrySet()) {
          Pos point = entry.getKey();
          Pos destination =
              origin.add(point.x() - source.x(), point.y() - source.y(), point.z() - source.z());
          require(
              Bukkit.isOwnedByCurrentRegion(location(destination), 1),
              "Replay crosses an unowned region");
          var cell = entry.getValue();
          if (cell.kind() == NavigationMap.Kind.UNKNOWN)
            block(destination).setType(Material.BARRIER, false);
          else if (!cell.state().isEmpty())
            block(destination).setBlockData(Bukkit.createBlockData(cell.state()), false);
          else block(destination).setType(Material.valueOf(cell.material()), false);
          translated.put(destination, cell);
        }
        Pos center = decoded.center;
        recordedMap =
            new NavigationMap(
                origin.add(
                    center.x() - source.x(), center.y() - source.y(), center.z() - source.z()),
                decoded.radius,
                decoded.vertical,
                translated);
      } else seedGeometry(origin);
      final Pos workGoal = goal;
      var data = new Settlement.Data();
      data.world = world.getUID().toString();
      data.center = origin;
      var village = new Settlement(data);
      if (recorded == null) village.craftingTable(origin.add(-2, 0, -2));
      var settlements = CivilizationsPlugin.class.getDeclaredField("settlements");
      settlements.setAccessible(true);
      Map<String, Settlement> villages = (Map<String, Settlement>) settlements.get(plugin);
      villages.put(village.id(), village);
      Villager actor = world.spawn(location(origin), Villager.class);
      actor.setAdult();
      actor.setPersistent(true);
      recordedInventory.forEach(
          (material, count) ->
              actor.getInventory().addItem(new ItemStack(Material.valueOf(material), count)));
      village.enroll(actor.getUniqueId().toString(), 20);
      var worker = new VillagerWorker(plugin, actor, village);
      var workerField = CivilizationsPlugin.class.getDeclaredField("workers");
      workerField.setAccessible(true);
      Map<String, VillagerWorker> workers = (Map<String, VillagerWorker>) workerField.get(plugin);
      workers.put(actor.getUniqueId().toString(), worker);
      ModelBackend teacher =
          new ModelBackend() {
            public boolean ready() {
              return !voxel && stage == 0;
            }

            public String status() {
              return voxel
                  ? "offline-voxel-controller"
                  : stage == 0 ? "deterministic-fixture" : "offline-reuse-only";
            }

            public void close() {}

            public String complete(String system, String report) {
              int count = calls.incrementAndGet();
              if (stage != 0 || count != 1)
                throw new IllegalStateException("Unexpected teacher call " + count);
              return PROGRAM;
            }
          };
      queue = new InferenceQueue(teacher, 4);
      service =
          new RecoveryExperiments(
              evidence,
              queue,
              () -> "deterministic-fixture:acquire-mine-portable-control",
              fixture.getLogger()::warning,
              voxel);
      replace(service);
      WorkMovementControl control =
          new WorkMovementControl(
              actor,
              message -> {
                throw new IllegalStateException(message);
              });
      control.working(true);
      var blocked = actor.getPathfinder().findPath(location(goal));
      require(
          recorded != null
              || blocked == null
              || blocked.getFinalPoint() == null
              || blocked.getFinalPoint().distanceSquared(location(goal)) >= 1,
          "Initial barrier is open");
      NavigationMap map =
          recorded != null
              ? recordedMap
              : NavigationTerrain.capture(
                  new Terrain() {
                    public int height(int x, int z) {
                      return Y - 1;
                    }

                    public boolean available(int x, int z) {
                      return Bukkit.isOwnedByCurrentRegion(new Location(world, x, Y, z), 1);
                    }

                    public String type(Pos p) {
                      return available(p.x(), p.z()) ? block(p).getType().name() : "UNKNOWN";
                    }

                    public String blockData(Pos p) {
                      return available(p.x(), p.z()) ? block(p).getBlockData().getAsString() : null;
                    }
                  },
                  origin,
                  8,
                  Set.of());
      // Drive the real worker's recovery so its ordinary ToolActions are the shared prerequisites.
      var navigationField = VillagerWorker.class.getDeclaredField("navigation");
      navigationField.setAccessible(true);
      var navigation = (WorkerNavigation) navigationField.get(worker);
      var skillField = WorkerNavigation.class.getDeclaredField("skillTrial");
      skillField.setAccessible(true);
      WorkerSkillTrial skill = (WorkerSkillTrial) skillField.get(navigation);
      long started = System.currentTimeMillis();
      require(
          skill.start(
              map,
              goal,
              recorded != null ? recordedReach : 0,
              recorded != null
                  ? recorded.get("failure").getAsString()
                  : "observed_stone_barrier_missing_tool",
              started),
          "No trial admitted");
      var trialField = WorkerSkillTrial.class.getDeclaredField("trial");
      trialField.setAccessible(true);
      String trial = ((RecoveryExperiments.Trial) trialField.get(skill)).id;
      var nextProgress = new AtomicLong(started + 10000);
      fixture
          .getLogger()
          .info(
              "SKILL TRANSFER START stage="
                  + stage
                  + " origin="
                  + origin
                  + " goal="
                  + goal
                  + " teacher="
                  + teacher.status()
                  + (recorded == null
                      ? "; initial inventory empty"
                      : "; initial inventory restored from captured case"));
      actor
          .getScheduler()
          .runAtFixedRate(
              fixture,
              task -> {
                try {
                  require(
                      plugin.worker(actor.getUniqueId().toString()) == worker,
                      "Competing controller");
                  control.working(true);
                  long now = System.currentTimeMillis();
                  navigation.experimentTick(now);
                  if (now >= nextProgress.get()) {
                    fixture
                        .getLogger()
                        .info(
                            "SKILL TRANSFER PROGRESS stage="
                                + stage
                                + " status="
                                + skill.status()
                                + " inventory="
                                + InventoryOps.summary(actor.getInventory()));
                    nextProgress.set(now + 10000);
                  }
                  if (skill.active() && now - started < 120000) return;
                  require(!skill.active(), "Fixture deadline: " + skill.status());
                  Map<String, Object> result = verifyWorld(stage, origin, workGoal, actor, trial);
                  task.cancel();
                  control.working(false);
                  navigation.stop();
                  workers.remove(actor.getUniqueId().toString(), worker);
                  villages.remove(village.id());
                  actor.remove();
                  persist(stage, result);
                } catch (Throwable error) {
                  task.cancel();
                  skill.cancel("fixture_failed: " + error);
                  control.working(false);
                  navigation.stop();
                  workers.remove(actor.getUniqueId().toString(), worker);
                  villages.remove(village.id());
                  actor.remove();
                  finish(error);
                }
              },
              () -> finish(new IllegalStateException("Fixture actor retired")),
              1,
              5);
    } catch (Throwable error) {
      finish(error);
    }
  }

  private void seedGeometry(Pos origin) {
    // Both complete captured volumes are identical except absolute world coordinates.
    for (int x = -8; x <= 8; x++)
      for (int z = -8; z <= 8; z++)
        for (int h = -11; h <= 12; h++)
          block(origin.add(x, h, z)).setType(h == -1 ? Material.STONE : Material.AIR, false);
    for (int x = -4; x <= 1; x++)
      for (int z = -3; z <= 3; z++)
        for (int h = 0; h <= 3; h++)
          if (x == -4 || x == 1 || z == -3 || z == 3 || h == 3)
            block(origin.add(x, h, z)).setType(Material.STONE, false);
    for (int x = 1; x <= 7; x++)
      for (int z = -1; z <= 1; z++)
        for (int h = 0; h <= 2; h++)
          block(origin.add(x, h, z))
              .setType(
                  z != 0 || x == 7 || h == 2 || x == barrier(origin)
                      ? Material.STONE
                      : Material.AIR,
                  false);
    block(origin.add(-2, 0, -2)).setType(Material.CRAFTING_TABLE, false);
    block(origin.add(-2, -1, 2)).setType(Material.DIRT, false);
    block(origin.add(-2, 0, 2)).setType(Material.OAK_LOG, false);
    block(origin.add(-2, 1, 2)).setType(Material.OAK_LOG, false);
    block(origin.add(-2, 2, 2))
        .setBlockData(
            Bukkit.createBlockData("minecraft:oak_leaves[distance=1,persistent=true]"), false);
    require(BlockRules.tree(block(origin.add(-2, 0, 2))), "Natural source tree not recognized");
  }

  private Map<String, Object> verifyWorld(
      int stage, Pos origin, Pos goal, Villager actor, String trial) {
    Pos actual =
        new Pos(
            actor.getLocation().getBlockX(),
            actor.getLocation().getBlockY(),
            actor.getLocation().getBlockZ());
    if (recorded != null) {
      require(actual.distance2(goal) <= recordedReach, "Recorded goal not reached: " + actual);

      require(calls.get() == 0, "Recorded replay called teacher");
      return Map.of(
          "stage",
          stage,
          "trial",
          trial,
          "origin",
          origin,
          "goal",
          goal,
          "final_position",
          actual,
          "inventory",
          InventoryOps.summary(actor.getInventory()),
          "teacher_calls_total",
          calls.get(),
          "verified",
          true,
          "source_map_id",
          recorded.get("source_map_id").getAsString());
    }
    require(actual.equals(goal), "Original goal not reached: " + actual);
    for (int h = 0; h <= 1; h++) {
      require(block(origin.add(barrier(origin), h, 0)).getType().isAir(), "Stone obstacle remains");
      require(
          block(origin.add(-2, h, 2)).getType().isAir(), "Required source log was not gathered");
    }
    require(
        block(origin.add(barrier(origin), -1, 0)).getType() == Material.STONE,
        "Route support removed");
    require(
        block(origin.add(barrier(origin), 2, 0)).getType() == Material.STONE, "Ceiling changed");
    require(
        block(origin.add(-2, 0, -2)).getType() == Material.CRAFTING_TABLE,
        "Crafting table changed");
    Map<String, Integer> inventory = InventoryOps.summary(actor.getInventory());
    require(
        inventory.equals(
            Map.of("WOODEN_PICKAXE", 1, "OAK_PLANKS", 3, "STICK", 2, "COBBLESTONE", 2)),
        "Gather/craft/mining conservation mismatch: " + inventory);
    int damage =
        Arrays.stream(actor.getInventory().getContents())
            .filter(Objects::nonNull)
            .filter(item -> item.getType() == Material.WOODEN_PICKAXE)
            .mapToInt(item -> ((Damageable) item.getItemMeta()).getDamage())
            .findFirst()
            .orElse(-1);
    require(damage == 2, "Mining did not spend exactly two tool durability: " + damage);
    require(calls.get() == (voxel ? 0 : 1), "Translated replay requested teacher inference");
    return Map.of(
        "stage",
        stage,
        "trial",
        trial,
        "origin",
        origin,
        "goal",
        goal,
        "final_position",
        actual,
        "inventory",
        inventory,
        "tool_damage",
        damage,
        "teacher_calls_total",
        calls.get(),
        "verified",
        true);
  }

  private void persist(int stage, Map<String, Object> result) {
    Bukkit.getAsyncScheduler()
        .runNow(
            fixture,
            task -> {
              try {
                service.close();
                queue.close();
                List<JsonObject> rows = new ArrayList<>();
                for (String line : Files.readAllLines(evidence.resolve("data/experiments.jsonl"))) {
                  var row = JsonParser.parseString(line).getAsJsonObject();
                  if (row.get("trial").getAsString().equals(result.get("trial"))) rows.add(row);
                }
                var outcome =
                    rows.stream()
                        .filter(row -> row.get("event").getAsString().equals("outcome"))
                        .map(row -> row.getAsJsonObject("data"))
                        .findFirst()
                        .orElseThrow();
                require(outcome.get("success").getAsBoolean(), "Physical success not persisted");
                require(
                    recorded != null
                        || outcome.getAsJsonObject("evidence").get("mined").getAsInt() == 2,
                    "Missing mining receipts");
                require(
                    outcome
                            .getAsJsonObject("evidence")
                            .get("acquire_instructions_satisfied")
                            .getAsInt()
                        == (voxel ? 0 : 1),
                    "Missing acquisition receipt");
                String portable =
                    outcome.has("portable_skill_id")
                        ? outcome.get("portable_skill_id").getAsString()
                        : "";
                if (stage == 0) firstPortableId = portable;
                else if (!voxel) {
                  require(
                      portable.equals(firstPortableId),
                      "Translated replay did not update original lesson");
                  require(
                      rows.stream()
                          .noneMatch(row -> row.get("event").getAsString().equals("requested")),
                      "Offline replay attempted inference");
                  require(
                      rows.stream()
                          .anyMatch(
                              row ->
                                  row.get("event").getAsString().equals("reuse")
                                      && row.getAsJsonObject("data")
                                          .get("basis")
                                          .getAsString()
                                          .contains("translated")),
                      "No persisted translated-lesson reuse receipt");
                }
                if (voxel) {
                  require(
                      rows.stream()
                          .anyMatch(row -> row.get("event").getAsString().equals("local_proposed")),
                      "Missing local controller proposal");
                  require(
                      Files.exists(evidence.resolve("recovery/physical-outcomes.jsonl")),
                      "Missing recovery learning data");
                  require(
                      !JsonParser.parseString(
                              Files.readString(evidence.resolve("recovery/candidate.json")))
                          .getAsJsonObject()
                          .get("promoted")
                          .getAsBoolean(),
                      "Unvalidated learner promoted");
                }
                stages.add(result);
                Files.writeString(
                    evidence.resolve("physical-stages.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(stages));
                fixture
                    .getLogger()
                    .info(
                        "SKILL TRANSFER STAGE PASS stage="
                            + stage
                            + (recorded == null
                                ? "; gathered two logs, crafted a wooden pickaxe, mined two stone,"
                                    + " retained two cobblestone, tool wear=2, arrived;"
                                : "; reached captured goal within its original arrival tolerance;"
                                    + " measured position and inventory saved in report;")
                            + " teacher_calls_total="
                            + calls);
                if (stage == 0 && recorded == null) load(1);
                else finish(null);
              } catch (Throwable error) {
                finish(error);
              }
            });
  }

  private org.bukkit.block.Block block(Pos p) {
    return world.getBlockAt(p.x(), p.y(), p.z());
  }

  private Location location(Pos p) {
    return new Location(world, p.x() + .5, p.y(), p.z() + .5);
  }

  private static void require(boolean condition, String reason) {
    if (!condition) throw new AssertionError(reason);
  }

  private void replace(RecoveryExperiments value) throws Exception {
    var field = CivilizationsPlugin.class.getDeclaredField("experiments");
    field.setAccessible(true);
    field.set(plugin, value);
    plugin.navigation().rules(value.rules());
  }

  private void finish(Throwable error) {
    if (!ending.compareAndSet(false, true)) return;
    Bukkit.getAsyncScheduler()
        .runNow(
            fixture,
            task -> {
              try {
                if (service != null) service.close();
                if (queue != null) queue.close();
                if (original != null) replace(original);
                var report = new LinkedHashMap<String, Object>();
                report.put("schema", 1);
                report.put(
                    "verified", error == null && stages.size() == (recorded != null ? 1 : 2));
                report.put("stages", stages);
                report.put("teacher_calls", calls.get());
                report.put(
                    "basis",
                    recorded != null
                        ? "Physical replay of captured live terrain with the voxel controller and"
                            + " no language model"
                        : voxel
                            ? "Autonomous voxel-generated recovery, two different barrier layouts,"
                                + " no teacher calls; physical outcomes collected, learned"
                                + " ranking not promoted"
                            : "Deterministic scripted executor baseline and durable translated"
                                + " skill reuse, not model intelligence or neural training");
                if (error != null) report.put("failure", error.toString());
                Files.createDirectories(evidence);
                Files.writeString(
                    evidence.resolve("report.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(report));
                fixture
                    .getLogger()
                    .info(
                        "SKILL TRANSFER "
                            + (error == null ? "PASS" : "FAIL: " + error)
                            + "; evidence="
                            + evidence);
              } catch (Throwable failure) {
                fixture.getLogger().severe("SKILL TRANSFER FINALIZE FAIL: " + failure);
              }
              List<CompletableFuture<Void>> released = new ArrayList<>();
              for (Pos chunk : tickets) {
                var done = new CompletableFuture<Void>();
                released.add(done);
                Bukkit.getRegionScheduler()
                    .execute(
                        fixture,
                        world,
                        chunk.x(),
                        chunk.z(),
                        () -> {
                          world.removePluginChunkTicket(chunk.x(), chunk.z(), fixture);
                          done.complete(null);
                        });
              }
              CompletableFuture.allOf(released.toArray(CompletableFuture[]::new))
                  .orTimeout(10, TimeUnit.SECONDS)
                  .whenComplete(
                      (ignored, failure) ->
                          Bukkit.getGlobalRegionScheduler()
                              .run(fixture, stop -> Bukkit.shutdown()));
            });
  }
}
