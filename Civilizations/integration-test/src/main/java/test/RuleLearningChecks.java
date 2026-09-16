package test;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.ai.*;
import dev.civilizations.core.*;
import dev.civilizations.learning.*;
import dev.civilizations.navigation.*;
import dev.civilizations.world.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.*;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Full worker-loop classification -> clearance -> repair -> learned reuse in a disposable world.
 */
final class RuleLearningChecks {
  private final JavaPlugin fixture;

  RuleLearningChecks(JavaPlugin fixture) {
    this.fixture = fixture;
  }

  void start() {
    Bukkit.getGlobalRegionScheduler()
        .runDelayed(
            fixture,
            t -> {
              World world = Bukkit.getWorlds().getFirst();
              world.setTime(1000);
              List<CompletableFuture<Void>> pending = new ArrayList<>();
              for (int x = -3; x <= 3; x++)
                for (int z = -3; z <= 3; z++) {
                  int cx = x, cz = z;
                  CompletableFuture<Void> ready = new CompletableFuture<>();
                  pending.add(ready);
                  world
                      .getChunkAtAsync(cx, cz, true)
                      .thenAccept(
                          c ->
                              Bukkit.getRegionScheduler()
                                  .execute(
                                      fixture,
                                      world,
                                      cx,
                                      cz,
                                      () -> {
                                        c.addPluginChunkTicket(fixture);
                                        ready.complete(null);
                                      }))
                      .exceptionally(
                          e -> {
                            ready.completeExceptionally(e);
                            return null;
                          });
                }
              CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                  .orTimeout(30, TimeUnit.SECONDS)
                  .whenComplete(
                      (v, e) -> {
                        if (e != null) fail(e);
                        else
                          Bukkit.getRegionScheduler()
                              .runDelayed(
                                  fixture, new Location(world, 0, 1, 0), q -> seed(world), 60);
                      });
            },
            60);
  }

  @SuppressWarnings("unchecked")
  void seed(World world) {
    try {
      CivilizationsPlugin plugin =
          (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
      int y = world.getHighestBlockYAt(0, 0) + 1;
      boolean wallCase = Boolean.getBoolean("civilizations.test.wall-work");
      boolean revisionCase = Boolean.getBoolean("civilizations.test.recovery-revision");
      for (int x = -8; x <= (wallCase ? 28 : 12); x++)
        for (int z = -8; z <= 8; z++)
          for (int dy = -1; dy <= 3; dy++)
            world.getBlockAt(x, y + dy, z).setType(dy == -1 ? Material.STONE : Material.AIR, false);
      Pos a = new Pos(2, y, 0), b = new Pos(4, y, 0), origin = new Pos(0, y, 0);
      world.getBlockAt(a.x(), y, a.z()).setType(Material.TRIPWIRE, false);
      world.getBlockAt(b.x(), y, b.z()).setType(Material.TRIPWIRE, false);
      var observed = BlockObservation.capture(world.getBlockAt(a.x(), y, a.z()));
      if (!observed.passable() || !observed.removable() || observed.dangerous())
        throw new AssertionError("Unexpected tripwire physics: " + observed);
      Settlement.Data data = new Settlement.Data();
      data.world = world.getUID().toString();
      data.center = origin;
      Settlement village = new Settlement(data);
      var villages = CivilizationsPlugin.class.getDeclaredField("settlements");
      villages.setAccessible(true);
      ((Map<String, Settlement>) villages.get(plugin)).put(village.id(), village);
      Villager actor = world.spawn(new Location(world, .5, y, .5), Villager.class);
      actor.setAdult();
      actor.setRotation(90, 0);
      AtomicInteger facedActions = new AtomicInteger();
      java.util.concurrent.atomic.AtomicReference<String> poseFailure =
          new java.util.concurrent.atomic.AtomicReference<>();
      Bukkit.getPluginManager()
          .registerEvents(
              new org.bukkit.event.Listener() {
                @org.bukkit.event.EventHandler
                public void work(WorkEvent event) {
                  if (!event.getVillager().equals(actor)
                      || !Set.of("PLACE", "CLEAR_ROUTE", "CLEAR_SITE").contains(event.getAction()))
                    return;
                  var delta =
                      event
                          .getBlock()
                          .getLocation()
                          .add(.5, .5, .5)
                          .toVector()
                          .subtract(actor.getEyeLocation().toVector());
                  double alignment =
                      actor.getEyeLocation().getDirection().dot(delta.clone().normalize());
                  if (alignment < .90 || delta.length() > 5)
                    poseFailure.set(
                        "Unfaced or out-of-reach work: " + event.getAction() + " dot=" + alignment);
                  else facedActions.incrementAndGet();
                }
              },
              fixture);
      village.enroll(actor.getUniqueId().toString(), 20);
      actor.getInventory().addItem(new ItemStack(Material.OAK_PLANKS, 2));
      Job first = new Job(Job.Kind.PLACE, "learning-repair", a, origin, "OAK_PLANKS", "AIR", null);
      Job second = new Job(Job.Kind.PLACE, "learning-repair", b, origin, "OAK_PLANKS", "AIR", null);
      village.addProject("learning-repair", List.of(first, second));
      List<Job> construction = new ArrayList<>();
      if (wallCase) {
        if (revisionCase) {
          world.getBlockAt(13, y - 1, -3).setType(Material.DIRT, false);
          world.getBlockAt(13, y, -3).setType(Material.OAK_LOG, false);
          world.getBlockAt(13, y + 1, -3).setType(Material.OAK_LOG, false);
          world.getBlockAt(13, y + 2, -3).setType(Material.OAK_LEAVES, false);
          for (int dy = 0; dy < 3; dy++)
            world.getBlockAt(19, y + dy, 3).setType(Material.DIRT, false);
        }
        Job roof =
            new Job(
                Job.Kind.PLACE,
                "fixture-roof",
                new Pos(6, y + 3, 0),
                new Pos(5, y, 0),
                "OAK_PLANKS",
                "AIR",
                null);
        construction.add(roof);
        village.addProject("fixture-roof", List.of(roof));
        Terrain floor =
            new Terrain() {
              public int height(int x, int z) {
                return revisionCase ? world.getHighestBlockYAt(x, z) : y - 1;
              }

              public boolean available(int x, int z) {
                return x >= -8 && x <= 28 && z >= -8 && z <= 8;
              }

              public String type(Pos p) {
                if (revisionCase)
                  return available(p.x(), p.z())
                      ? world.getBlockAt(p.x(), p.y(), p.z()).getType().name()
                      : "UNKNOWN";
                return p.y() == y - 1 ? "GRASS_BLOCK" : p.y() < y - 1 ? "STONE" : "AIR";
              }
            };
        var blueprint =
            new dev.civilizations.design.Blueprint(
                "wall",
                "Protect local storage",
                0,
                -3,
                0,
                0,
                3,
                "north",
                List.of(
                    new dev.civilizations.design.Blueprint.Point(-3, -3),
                    new dev.civilizations.design.Blueprint.Point(3, -3),
                    new dev.civilizations.design.Blueprint.Point(3, 3),
                    new dev.civilizations.design.Blueprint.Point(-3, 3)));
        var compiled =
            new dev.civilizations.design.DesignCompiler()
                .compile(
                    blueprint,
                    floor,
                    new Pos(16, y, 0),
                    "design-wall-fixture",
                    p -> false,
                    List.of(new Pos(16, y, 0), new Pos(150, y, 0)));
        construction.addAll(compiled.jobs());
        village.addProject("design-wall-fixture", compiled.jobs());
        int wallStone =
            (int) compiled.jobs().stream().filter(j -> j.material.equals("COBBLESTONE")).count();
        actor
            .getInventory()
            .addItem(
                new ItemStack(Material.OAK_PLANKS, 1),
                new ItemStack(Material.COBBLESTONE, 10),
                new ItemStack(Material.OAK_FENCE_GATE, 1));
        Pos supply = new Pos(-3, y, -3);
        world.getBlockAt(supply.x(), supply.y(), supply.z()).setType(Material.CHEST, false);
        var storage =
            (org.bukkit.block.Chest)
                world.getBlockAt(supply.x(), supply.y(), supply.z()).getState();
        storage.getInventory().addItem(new ItemStack(Material.COBBLESTONE, wallStone - 10));
        village.chest(supply);
        village.stock(
            supply, InventoryOps.summary(storage.getInventory()), System.currentTimeMillis());
      }
      AtomicInteger calls = new AtomicInteger();
      AtomicInteger firstCalls = new AtomicInteger(-1);
      Path evidence =
          plugin
              .getDataFolder()
              .toPath()
              .resolve("CoreAI-rule-fixture")
              .resolve(world.getUID().toString());
      Files.createDirectories(evidence);
      boolean real = System.getenv("CIV_TEST_MODEL_TOKEN") != null;
      String replayPath = System.getProperty("civilizations.test.rule-program");
      String replay = replayPath == null ? null : Files.readString(Path.of(replayPath));
      ModelBackend backend =
          new ModelBackend() {
            public boolean ready() {
              return true;
            }

            public String status() {
              return replay != null
                  ? "saved DeepSeek program replay"
                  : real ? "existing local DeepSeek" : "deterministic fixture";
            }

            public void close() {}

            public String complete(String system, String report) {
              throw new UnsupportedOperationException();
            }

            public String complete(
                String system, String report, ReasoningMode mode, String schema, long deadline)
                throws Exception {
              calls.incrementAndGet();
              if (!report.contains("physical_block_probes"))
                throw new IllegalStateException("Missing physical probes");
              if (replay != null) return replay;
              if (revisionCase) {
                if (calls.get() == 1)
                  return """
                  {"explanation":"First classify the measured clutter, then attempt an impossible underground walk to exercise recovery revision.","steps":[
                  {"op":"CLASSIFY","x":2,"y":0,"z":0,"material":"PASSABLE"},
                  {"op":"WALK","x":2,"y":-2,"z":0,"material":""},
                  {"op":"CLEAR","x":2,"y":0,"z":0,"material":""}]}
                  """;
                var data = com.google.gson.JsonParser.parseString(report).getAsJsonObject();
                if (!data.has("failed_execution"))
                  throw new AssertionError("Revision missing exact failed execution");
                var target = data.getAsJsonObject("goal_relative");
                String source =
                    "{\"explanation\":\"The underground walk failed. Clear the original site"
                        + " directly from supported ground, preserving the"
                        + " task.\",\"steps\":[{\"op\":\"CLEAR\",\"x\":"
                        + target.get("x")
                        + ",\"y\":"
                        + target.get("y")
                        + ",\"z\":"
                        + target.get("z")
                        + ",\"material\":\"\"}]}";
                Files.writeString(evidence.resolve("revision-request.json"), report);
                return source;
              }
              if (real) {
                try {
                  String source = LocalRuleTeacher.complete(system, report, schema, deadline);
                  Files.writeString(
                      evidence.resolve("teacher-final-" + calls.get() + ".json"), source);
                  return source;
                } catch (Exception error) {
                  Files.writeString(
                      evidence.resolve("teacher-error-" + calls.get() + ".txt"), error.toString());
                  throw error;
                }
              }
              String source =
                  """
                  {"explanation":"Probe shows harmless removable clutter; classify, clear the repair site, and trial a wider map.","steps":[
                  {"op":"TUNE","x":500,"y":0,"z":0,"material":"construction.face_ms"},
                  {"op":"CLASSIFY","x":2,"y":0,"z":0,"material":"PASSABLE"},
                  {"op":"SEARCH","x":32,"y":0,"z":0,"material":""},
                  {"op":"CLEAR","x":2,"y":0,"z":0,"material":""},
                  {"op":"VERIFY","x":0,"y":0,"z":0,"material":""}]}
                  """;
              return wallCase
                  ? source.replace("\"x\":500", "\"x\":125").replace("\"x\":32", "\"x\":64")
                  : source;
            }
          };
      InferenceQueue queue = new InferenceQueue(backend, 4);
      queue.observe(
          (agent, event) -> plugin.debug(village.id(), agent, "fixture_inference", event));
      RecoveryExperiments service =
          new RecoveryExperiments(
              evidence, queue, () -> backend.status(), fixture.getLogger()::warning);
      var field = CivilizationsPlugin.class.getDeclaredField("experiments");
      field.setAccessible(true);
      field.set(plugin, service);
      plugin.navigation().rules(service.rules(), 48);
      VillagerWorker worker = new VillagerWorker(plugin, actor, village);
      // This selector deliberately isolates generated recovery programs. The lesson-memory
      // selector exercises normal automatic observation without asking a teacher first.
      var lessonsField = VillagerWorker.class.getDeclaredField("lessons");
      lessonsField.setAccessible(true);
      var observeAt = BlockLessons.class.getDeclaredField("nextObservation");
      observeAt.setAccessible(true);
      observeAt.setLong(lessonsField.get(worker), Long.MAX_VALUE);
      var workers = CivilizationsPlugin.class.getDeclaredField("workers");
      workers.setAccessible(true);
      ((Map<String, VillagerWorker>) workers.get(plugin))
          .put(actor.getUniqueId().toString(), worker);
      worker.start();
      long started = System.currentTimeMillis();
      actor
          .getScheduler()
          .runAtFixedRate(
              fixture,
              t -> {
                try {
                  if (first.complete) firstCalls.compareAndSet(-1, calls.get());
                  if (first.complete
                      && second.complete
                      && construction.stream().allMatch(j -> j.complete)) {
                    if (Boolean.getBoolean("civilizations.test.framework")) {
                      var experiences = worker.agentExperiences();
                      for (Job expected : List.of(first, second)) {
                        var verified =
                            experiences.stream()
                                .filter(
                                    e ->
                                        e.attempt().action().id().equals(expected.id)
                                            && e.outcome().status()
                                                == dev.coreai.agent.Outcome.Status.SUCCEEDED)
                                .findFirst()
                                .orElseThrow(
                                    () ->
                                        new AssertionError(
                                            "Missing CoreAI receipt for " + expected.id));
                        if (!verified
                            .outcome()
                            .evidence()
                            .object()
                            .get("observed_block")
                            .getAsString()
                            .equals("OAK_PLANKS"))
                          throw new AssertionError(
                              "CoreAI outcome did not contain the real block observation");
                      }
                    }
                    for (Job built : construction)
                      if (built.kind == Job.Kind.PLACE)
                        if (world
                                .getBlockAt(built.target.x(), built.target.y(), built.target.z())
                                .getType()
                            != Material.valueOf(built.material))
                          throw new AssertionError("Unbuilt construction target " + built.target);
                    if (wallCase
                        && (InventoryOps.count(actor.getInventory(), Material.COBBLESTONE) != 0
                            || InventoryOps.count(actor.getInventory(), Material.OAK_FENCE_GATE)
                                != 0)) throw new AssertionError("Wall material accounting");
                    if (poseFailure.get() != null || facedActions.get() < 4)
                      throw new AssertionError(
                          "Facing verification: "
                              + poseFailure.get()
                              + " actions="
                              + facedActions.get());
                    if (!revisionCase
                        && !real
                        && replay == null
                        && service.rules().parameter("another-worker", "construction.face_ms", 250)
                            != (wallCase ? 125 : 500))
                      throw new AssertionError("Tuning was not persisted and shared");
                    if (world.getBlockAt(a.x(), y, a.z()).getType() != Material.OAK_PLANKS
                        || world.getBlockAt(b.x(), y, b.z()).getType() != Material.OAK_PLANKS
                        || InventoryOps.count(actor.getInventory(), Material.OAK_PLANKS) != 0
                        || InventoryOps.count(actor.getInventory(), Material.STRING) != 2)
                      throw new AssertionError(
                          "Repair blocks, consumed planks or retained clutter drops incorrect: "
                              + InventoryOps.summary(actor.getInventory()));
                    if (firstCalls.get() < 1 || calls.get() != firstCalls.get())
                      throw new AssertionError(
                          "Expected one learning request followed by reuse; actual=" + calls);
                    if (service
                            .rules()
                            .rule("another-worker", observed.material(), observed.state())
                        == null) throw new AssertionError("No published classification");
                    if (!revisionCase
                        && !real
                        && replay == null
                        && plugin.navigation().radiusFor("another-worker") != (wallCase ? 64 : 32))
                      throw new AssertionError("Search edit not learned");
                    if (revisionCase) {
                      if (calls.get() != 2)
                        throw new AssertionError(
                            "Expected failed program and one fresh revision; got " + calls);
                      if (InventoryOps.count(actor.getInventory(), Material.OAK_LOG) != 2
                          || InventoryOps.count(actor.getInventory(), Material.DIRT) != 2)
                        throw new AssertionError(
                            "Construction preparation drops lost: "
                                + InventoryOps.summary(actor.getInventory()));
                      fixture
                          .getLogger()
                          .info(
                              "RECOVERY WORKFLOW PASS: failed underground WALK revised with fresh"
                                  + " observations; original repair resumed; learned clutter rule"
                                  + " reused; full wall built after tree/dirt clearance; shared"
                                  + " chest supplied exact stone cost; retained two logs, two dirt"
                                  + " and two string");
                    }
                    t.cancel();
                    worker.stop();
                    fixture
                        .getLogger()
                        .info(
                            "RULE LEARNING PASS: extra construction blocks="
                                + construction.size()
                                + "; two actual worker-loop repairs; repair planks"
                                + " consumed; two string retained; classification published and"
                                + " reused; facing verified; inference requests="
                                + calls
                                + "; teacher="
                                + backend.status());
                    service.close();
                    queue.close();
                    Bukkit.getGlobalRegionScheduler()
                        .runDelayed(fixture, q -> Bukkit.shutdown(), 20);
                  } else if (System.currentTimeMillis() - started > (wallCase ? 360000 : 210000))
                    throw new AssertionError("Rule-learning deadline; " + worker.status());
                } catch (Throwable e) {
                  t.cancel();
                  worker.stop();
                  service.close();
                  queue.close();
                  fail(e);
                }
              },
              () -> {},
              5,
              5);
    } catch (Throwable e) {
      fail(e);
    }
  }

  private void fail(Throwable error) {
    fixture.getLogger().severe("RULE LEARNING FAIL: " + error);
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> Bukkit.shutdown(), 20);
  }
}
