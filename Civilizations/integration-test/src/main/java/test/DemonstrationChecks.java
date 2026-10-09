package test;

import com.google.gson.*;
import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.ai.*;
import dev.civilizations.core.*;
import dev.civilizations.learning.*;
import dev.civilizations.navigation.NavigationMap;
import dev.civilizations.world.*;
import dev.coreai.PolicyLibrary;
import dev.coreai.SkillProgram;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.bukkit.*;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable, physically measured lessons. A deterministic teacher is never labeled a model. */
final class DemonstrationChecks {
  private record Layout(
      String name,
      String split,
      int x,
      int z,
      int dx,
      int dz,
      int length,
      Material foliage,
      boolean soil) {
    Pos at(int y, int distance) {
      return new Pos(x + dx * distance, y, z + dz * distance);
    }
  }

  private static final List<Layout> LAYOUTS =
      List.of(
          new Layout("oak-east", "train", -9, 0, 1, 0, 6, Material.OAK_LEAVES, false),
          new Layout("birch-south", "train", 6, -9, 0, 1, 5, Material.BIRCH_LEAVES, false),
          new Layout("spruce-west-soil", "holdout", 9, 6, -1, 0, 7, Material.SPRUCE_LEAVES, true));
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
  private final JavaPlugin fixture;
  private final boolean realTeacher = Boolean.getBoolean("civilizations.test.real-teacher");
  private final List<Map<String, Object>> results = new ArrayList<>();
  private final AtomicBoolean ending = new AtomicBoolean();
  private World world;
  private CivilizationsPlugin plugin;
  private RecoveryExperiments originalService;
  private Path root;
  private int y;

  DemonstrationChecks(JavaPlugin fixture) {
    this.fixture = fixture;
  }

  void start() {
    Bukkit.getGlobalRegionScheduler()
        .runDelayed(
            fixture,
            task -> {
              try {
                if (realTeacher
                    && (System.getenv("CIV_TEST_MODEL_TOKEN") == null
                        || !Objects.toString(System.getenv("CIV_TEST_MODEL_SHA256"), "")
                            .matches("[a-fA-F0-9]{64}")))
                  throw new IllegalArgumentException(
                      "Real teacher requires token and model SHA256 in environment");
                world = Bukkit.getWorlds().getFirst();
                world.setTime(1000);
                plugin = (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
                originalService = plugin.experiments();
                root =
                    plugin
                        .getDataFolder()
                        .toPath()
                        .resolve("CoreAI-demonstration-" + world.getUID());
                List<CompletableFuture<Void>> loaded = new ArrayList<>();
                // Explicit tickets keep the entire observed area alive without a connected player.
                for (int x = -2; x <= 2; x++)
                  for (int z = -2; z <= 2; z++) {
                    int cx = x, cz = z;
                    var ready = new CompletableFuture<Void>();
                    loaded.add(ready);
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
                                          ready.complete(null);
                                        }))
                        .exceptionally(
                            error -> {
                              ready.completeExceptionally(error);
                              return null;
                            });
                  }
                CompletableFuture.allOf(loaded.toArray(CompletableFuture[]::new))
                    .orTimeout(30, TimeUnit.SECONDS)
                    .whenComplete(
                        (ignored, error) -> {
                          if (error != null) finish(error);
                          else
                            Bukkit.getRegionScheduler()
                                .runDelayed(
                                    fixture,
                                    new Location(world, 0, 1, 0),
                                    tick -> {
                                      y = world.getHighestBlockYAt(0, 0) + 4;
                                      runCase(0);
                                    },
                                    60);
                        });
              } catch (Throwable error) {
                finish(error);
              }
            },
            60);
  }

  @SuppressWarnings("unchecked")
  private void runCase(int index) {
    if (index >= LAYOUTS.size()) {
      finish(null);
      return;
    }
    Layout layout = LAYOUTS.get(index);
    try {
      Pos origin = layout.at(y, 0), goal = layout.at(y, layout.length());
      // Enclosed two-high corridor: no hidden route around or over the foliage.
      for (int distance = -1; distance <= layout.length() + 1; distance++)
        for (int side = -1; side <= 1; side++)
          for (int height = -1; height <= 2; height++) {
            Pos p = layout.at(y + height, distance).add(-layout.dz() * side, 0, layout.dx() * side);
            boolean border =
                side != 0
                    || distance == -1
                    || distance == layout.length() + 1
                    || height == -1
                    || height == 2;
            world
                .getBlockAt(p.x(), p.y(), p.z())
                .setType(border ? Material.STONE : Material.AIR, false);
          }
      List<Pos> obstacles = new ArrayList<>();
      for (int height = 0; height <= 1; height++) {
        Pos p = layout.at(y + height, 2);
        Leaves leaves = (Leaves) layout.foliage().createBlockData();
        leaves.setPersistent(false);
        leaves.setDistance(1); // Natural removable foliage, with no decay before executor action.
        world.getBlockAt(p.x(), p.y(), p.z()).setBlockData(leaves, false);
        obstacles.add(p);
        if (layout.soil()) {
          Pos soil = layout.at(y + height, 4);
          world.getBlockAt(soil.x(), soil.y(), soil.z()).setType(Material.DIRT, false);
          obstacles.add(soil);
        }
      }
      Settlement.Data data = new Settlement.Data();
      data.world = world.getUID().toString();
      data.center = origin;
      Settlement village = new Settlement(data);
      var villageField = CivilizationsPlugin.class.getDeclaredField("settlements");
      villageField.setAccessible(true);
      Map<String, Settlement> villages = (Map<String, Settlement>) villageField.get(plugin);
      villages.put(village.id(), village);
      Villager actor = world.spawn(location(origin), Villager.class);
      actor.setAdult();
      actor.setPersistent(true);
      village.enroll(actor.getUniqueId().toString(), 20);
      var ownership = new VillagerWorker(plugin, actor, village);
      var workersField = CivilizationsPlugin.class.getDeclaredField("workers");
      workersField.setAccessible(true);
      Map<String, VillagerWorker> workers = (Map<String, VillagerWorker>) workersField.get(plugin);
      workers.put(actor.getUniqueId().toString(), ownership);
      var calls = new AtomicInteger();
      ModelBackend teacher = teacher(calls);
      InferenceQueue queue = new InferenceQueue(teacher, 4);
      Path evidence = root.resolve(layout.name());
      RecoveryExperiments service =
          new RecoveryExperiments(
              evidence, queue, () -> provenance(), fixture.getLogger()::warning);
      replaceService(service);
      WorkMovementControl control =
          new WorkMovementControl(
              actor,
              message -> {
                throw new IllegalStateException(message);
              });
      control.working(true);
      var initialPath = actor.getPathfinder().findPath(location(goal));
      if (initialPath != null
          && initialPath.getFinalPoint() != null
          && initialPath.getFinalPoint().distanceSquared(location(goal)) < 1)
        throw new AssertionError("Fixture route is already open before clearance");
      NavigationMap map =
          NavigationTerrain.capture(
              new Terrain() {
                public int height(int x, int z) {
                  return y - 1;
                }

                public boolean available(int x, int z) {
                  return Bukkit.isOwnedByCurrentRegion(new Location(world, x, y, z), 1);
                }

                public String type(Pos p) {
                  return available(p.x(), p.z())
                      ? world.getBlockAt(p.x(), p.y(), p.z()).getType().name()
                      : "UNKNOWN";
                }

                public String blockData(Pos p) {
                  return available(p.x(), p.z())
                      ? world.getBlockAt(p.x(), p.y(), p.z()).getBlockData().getAsString()
                      : null;
                }
              },
              origin,
              8,
              Set.of());
      var error = new AtomicReference<String>();
      WorkerNavigation navigation =
          new WorkerNavigation(
              plugin,
              actor,
              village,
              (now, reason) -> error.compareAndSet(null, "navigation_failed: " + reason));
      var skillField = WorkerNavigation.class.getDeclaredField("skillTrial");
      skillField.setAccessible(true);
      WorkerSkillTrial skill = (WorkerSkillTrial) skillField.get(navigation);
      long started = System.currentTimeMillis();
      if (!skill.start(map, goal, 0, "native_path_does_not_reach_original_goal", started))
        throw new IllegalStateException("No recovery experiment admitted");
      fixture
          .getLogger()
          .info(
              "DEMONSTRATION START case="
                  + layout.name()
                  + " split="
                  + layout.split()
                  + " origin="
                  + origin
                  + " goal="
                  + goal
                  + " teacher="
                  + teacher.status()
                  + "; 25 chunk tickets retained; initial native path cannot reach goal");
      Map<String, Object> recipe = new LinkedHashMap<>();
      recipe.put("schema", 1);
      recipe.put("lesson", "natural_obstruction_recovery");
      recipe.put("layout", layout);
      recipe.put("teacher", JsonParser.parseString(provenance()));
      recipe.put("world", world.getUID().toString());
      recipe.put("origin", origin);
      recipe.put("goal", goal);
      recipe.put("obstacles_before", obstacles);
      recipe.put("initial_inventory", Map.of());
      recipe.put("chunk_loading", "25 fixture-owned chunk tickets until suite completion");
      recipe.put("independent_case_library", true);
      recipe.put(
          "selection", "heldout layout is excluded from training; no cross-case skill reuse");
      AtomicLong nextProgress = new AtomicLong(started + 15000);
      AtomicBoolean completed = new AtomicBoolean();
      actor
          .getScheduler()
          .runAtFixedRate(
              fixture,
              task -> {
                try {
                  if (plugin.worker(actor.getUniqueId().toString()) != ownership)
                    throw new AssertionError("Fixture actor has competing controllers");
                  control.working(true);
                  long now = System.currentTimeMillis();
                  navigation.experimentTick(now);
                  if (now >= nextProgress.get()) {
                    fixture
                        .getLogger()
                        .info(
                            "DEMONSTRATION PROGRESS case="
                                + layout.name()
                                + " elapsed_ms="
                                + (now - started)
                                + " status="
                                + skill.status()
                                + " position="
                                + actor.getLocation().toVector()
                                + " teacher_calls="
                                + calls);
                    nextProgress.set(now + 15000);
                  }
                  if (error.get() != null) throw new AssertionError(error.get());
                  if (skill.active() && now - started < (realTeacher ? 300000 : 90000)) return;
                  if (skill.active())
                    throw new AssertionError("lesson_deadline: " + skill.status());
                  Pos actual =
                      new Pos(
                          actor.getLocation().getBlockX(),
                          actor.getLocation().getBlockY(),
                          actor.getLocation().getBlockZ());
                  if (!actual.equals(goal))
                    throw new AssertionError("Original goal not reached: " + actual);
                  for (Pos p : obstacles)
                    if (!world.getBlockAt(p.x(), p.y(), p.z()).getType().isAir())
                      throw new AssertionError("Obstacle remains at " + p);
                  Map<String, Integer> inventory = InventoryOps.summary(actor.getInventory());
                  validateDrops(layout, inventory);
                  recipe.put("verified", true);
                  recipe.put("final_position", actual);
                  recipe.put("final_inventory", inventory);
                  recipe.put(
                      "drop_accounting",
                      "No supplied items; exactly two dirt for soil case; foliage output is"
                          + " stochastic and checked against valid material/count bounds");
                } catch (Throwable failure) {
                  recipe.put("verified", false);
                  recipe.put("failure", failure.toString());
                  recipe.put("final_inventory", InventoryOps.summary(actor.getInventory()));
                  recipe.put("final_position", actor.getLocation().toVector().toString());
                  skill.cancel("demonstration_failed: " + failure);
                }
                if (!completed.compareAndSet(false, true)) return;
                task.cancel();
                recipe.put("elapsed_ms", System.currentTimeMillis() - started);
                recipe.put("teacher_calls", calls.get());
                control.working(false);
                navigation.stop();
                workers.remove(actor.getUniqueId().toString(), ownership);
                villages.remove(village.id());
                actor.remove();
                Bukkit.getAsyncScheduler()
                    .runNow(
                        fixture,
                        async -> {
                          try {
                            service.close();
                            queue.close();
                            checkJournal(evidence, Boolean.TRUE.equals(recipe.get("verified")));
                            Files.writeString(evidence.resolve("lesson.json"), JSON.toJson(recipe));
                            synchronized (results) {
                              results.add(recipe);
                            }
                            fixture
                                .getLogger()
                                .info(
                                    "DEMONSTRATION "
                                        + (Boolean.TRUE.equals(recipe.get("verified"))
                                            ? "CASE PASS"
                                            : "CASE FAIL")
                                        + " case="
                                        + layout.name()
                                        + " split="
                                        + layout.split()
                                        + " teacher_calls="
                                        + calls
                                        + " measured_receipt="
                                        + evidence.resolve("lesson.json")
                                        + (recipe.containsKey("failure")
                                            ? " reason=" + recipe.get("failure")
                                            : ""));
                            Layout next = LAYOUTS.get(Math.min(index + 1, LAYOUTS.size() - 1));
                            Bukkit.getRegionScheduler()
                                .runDelayed(
                                    fixture,
                                    location(next.at(y, 0)),
                                    tick -> runCase(index + 1),
                                    5);
                          } catch (Throwable failure) {
                            finish(failure);
                          }
                        });
              },
              () -> finish(new IllegalStateException("Fixture actor retired")),
              1,
              5);
    } catch (Throwable error) {
      finish(error);
    }
  }

  private ModelBackend teacher(AtomicInteger calls) {
    return new ModelBackend() {
      public boolean ready() {
        return true;
      }

      public String status() {
        return realTeacher ? "local-model-no-scripted-fallback" : "deterministic-fixture";
      }

      public void close() {}

      public String complete(String system, String report) {
        if (realTeacher)
          throw new IllegalStateException("Real teacher requires schema and deadline");
        return baseline(report, calls.incrementAndGet());
      }

      public String complete(
          String system, String report, ReasoningMode mode, String schema, long deadline)
          throws Exception {
        if (!realTeacher) return complete(system, report);
        calls.incrementAndGet();
        return LocalRuleTeacher.complete(system, report, schema, deadline);
      }
    };
  }

  private String provenance() {
    return new Gson()
        .toJson(
            realTeacher
                ? new PolicyLibrary.Provenance(
                    Objects.toString(
                        System.getenv("CIV_TEST_MODEL_ID"), "DeepSeek-R1-0528-Qwen3-8B"),
                    Objects.toString(System.getenv("CIV_TEST_MODEL_ARTIFACT"), "local-gguf"),
                    System.getenv("CIV_TEST_MODEL_SHA256"),
                    Objects.toString(System.getenv("CIV_TEST_MODEL_LICENSE"), "unverified"),
                    "local physical demonstration; teacher output measured by executor; no scripted"
                        + " fallback")
                : new PolicyLibrary.Provenance(
                    "deterministic-fixture",
                    "foliage-baseline-v1",
                    "",
                    "project fixture",
                    "scripted execution baseline; not model-generated training knowledge"));
  }

  /**
   * Scripted baseline uses the same observed report, never hidden coordinates or a world handle.
   */
  private static String baseline(String report, int call) {
    JsonObject observation = JsonParser.parseString(report).getAsJsonObject();
    Pos goal = new Gson().fromJson(observation.get("goal_relative"), Pos.class);
    List<Pos> obstacles = new ArrayList<>();
    for (JsonElement candidate : observation.getAsJsonArray("natural_clear_candidates")) {
      Pos p = new Gson().fromJson(candidate, Pos.class);
      if (p.y() >= 0
          && p.y() <= 1
          && p.x() * goal.z() == p.z() * goal.x()
          && p.x() * goal.x() + p.z() * goal.z() > 0) obstacles.add(p);
    }
    obstacles.sort(
        Comparator.comparingLong((Pos p) -> p.horizontal2(new Pos(0, 0, 0)))
            .thenComparing(Comparator.comparingInt(Pos::y).reversed()));
    List<SkillProgram.Step> steps = new ArrayList<>();
    if (call == 1 && !obstacles.isEmpty()) {
      Pos p = obstacles.getFirst();
      steps.add(new SkillProgram.Step(SkillProgram.Op.OBSERVE, p.x(), p.y(), p.z(), ""));
    } else {
      for (int i = 0; i < obstacles.size(); i++) {
        Pos p = obstacles.get(i);
        steps.add(new SkillProgram.Step(SkillProgram.Op.CLEAR, p.x(), p.y(), p.z(), ""));
        if (i + 1 == obstacles.size()
            || obstacles.get(i + 1).x() != p.x()
            || obstacles.get(i + 1).z() != p.z())
          steps.add(new SkillProgram.Step(SkillProgram.Op.WALK, p.x(), 0, p.z(), ""));
      }
      steps.add(new SkillProgram.Step(SkillProgram.Op.WALK, goal.x(), goal.y(), goal.z(), ""));
      steps.add(new SkillProgram.Step(SkillProgram.Op.VERIFY, 0, 0, 0, ""));
    }
    return new Gson()
        .toJson(new SkillProgram("Deterministic fixture baseline from observed cells", steps));
  }

  private static void validateDrops(Layout layout, Map<String, Integer> items) {
    String sapling = layout.foliage().name().replace("_LEAVES", "_SAPLING");
    Set<String> allowed = new HashSet<>(Set.of(sapling, "STICK"));
    if (layout.foliage() == Material.OAK_LEAVES) allowed.add("APPLE");
    if (layout.soil()) allowed.add("DIRT");
    if (!allowed.containsAll(items.keySet()))
      throw new AssertionError("Unexpected acquired item: " + items);
    if (items.getOrDefault("DIRT", 0) != (layout.soil() ? 2 : 0))
      throw new AssertionError("Dirt material conservation failed: " + items);
    for (var item : items.entrySet())
      if (item.getValue() < 0 || item.getValue() > (item.getKey().equals("STICK") ? 4 : 2))
        throw new AssertionError("Foliage drop bound exceeded: " + items);
  }

  private static void checkJournal(Path evidence, boolean physicallyVerified) throws Exception {
    List<JsonObject> receipts = new ArrayList<>();
    for (String line : Files.readAllLines(evidence.resolve("data/experiments.jsonl")))
      if (!line.isBlank()) receipts.add(JsonParser.parseString(line).getAsJsonObject());
    List<JsonObject> outcomes =
        receipts.stream()
            .filter(row -> row.get("event").getAsString().equals("outcome"))
            .map(row -> row.getAsJsonObject("data"))
            .toList();
    if (physicallyVerified
        && (outcomes.size() != 1 || !outcomes.getFirst().get("success").getAsBoolean()))
      throw new AssertionError("Physical result lacks one matching persisted successful outcome");
    if (physicallyVerified
        && receipts.stream().noneMatch(row -> row.get("event").getAsString().equals("step")))
      throw new AssertionError("Arrival lacks executed instruction receipts");
  }

  private Location location(Pos p) {
    return new Location(world, p.x() + .5, p.y(), p.z() + .5);
  }

  private void replaceService(RecoveryExperiments service) throws Exception {
    var field = CivilizationsPlugin.class.getDeclaredField("experiments");
    field.setAccessible(true);
    field.set(plugin, service);
    plugin.navigation().rules(service.rules());
  }

  private void finish(Throwable error) {
    if (!ending.compareAndSet(false, true)) return;
    Bukkit.getAsyncScheduler()
        .runNow(
            fixture,
            async -> {
              try {
                if (root != null) {
                  Files.createDirectories(root);
                  Map<String, Object> report = new LinkedHashMap<>();
                  report.put("schema", 1);
                  report.put("teacher_mode", realTeacher ? "local_model" : "deterministic_fixture");
                  report.put("cases", results);
                  report.put(
                      "verified_count",
                      results.stream().filter(r -> Boolean.TRUE.equals(r.get("verified"))).count());
                  report.put(
                      "limitation",
                      "Three independent physical cases; no general intelligence, autonomous"
                          + " curriculum generation, trained selector or neural improvement"
                          + " established");
                  if (error != null) report.put("fixture_error", error.toString());
                  Files.writeString(root.resolve("report.json"), JSON.toJson(report));
                }
                if (plugin != null && originalService != null) {
                  if (plugin.experiments() != originalService) plugin.experiments().close();
                  replaceService(originalService);
                }
                if (error != null)
                  fixture.getLogger().severe("DEMONSTRATION FIXTURE FAIL: " + error);
                else
                  fixture
                      .getLogger()
                      .info(
                          "DEMONSTRATION COMPLETE verified="
                              + results.stream()
                                  .filter(r -> Boolean.TRUE.equals(r.get("verified")))
                                  .count()
                              + "/"
                              + LAYOUTS.size()
                              + "; teacher="
                              + (realTeacher ? "real local model" : "deterministic fixture")
                              + "; report="
                              + root.resolve("report.json"));
              } catch (Throwable failure) {
                fixture.getLogger().severe("DEMONSTRATION FINALIZE FAIL: " + failure);
              }
              List<CompletableFuture<Void>> released = new ArrayList<>();
              if (world != null)
                for (int x = -2; x <= 2; x++)
                  for (int z = -2; z <= 2; z++) {
                    int cx = x, cz = z;
                    CompletableFuture<Void> done = new CompletableFuture<>();
                    released.add(done);
                    Bukkit.getRegionScheduler()
                        .execute(
                            fixture,
                            world,
                            cx,
                            cz,
                            () -> {
                              try {
                                world.removePluginChunkTicket(cx, cz, fixture);
                                done.complete(null);
                              } catch (Throwable failure) {
                                done.completeExceptionally(failure);
                              }
                            });
                  }
              CompletableFuture.allOf(released.toArray(CompletableFuture[]::new))
                  .orTimeout(10, TimeUnit.SECONDS)
                  .whenComplete(
                      (ignored, failure) ->
                          Bukkit.getGlobalRegionScheduler()
                              .run(
                                  fixture,
                                  task -> {
                                    if (failure == null)
                                      fixture
                                          .getLogger()
                                          .info(
                                              "DEMONSTRATION chunk tickets released; shutting down"
                                                  + " disposable server");
                                    else
                                      fixture
                                          .getLogger()
                                          .warning(
                                              "DEMONSTRATION ticket release incomplete: "
                                                  + failure
                                                  + "; shutting down disposable server");
                                    Bukkit.shutdown();
                                  }));
            });
  }
}
