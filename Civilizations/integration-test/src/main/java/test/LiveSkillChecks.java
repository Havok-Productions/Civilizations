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
import org.bukkit.*;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Combined observation and bridge case: one executor, real blocks, inventory and persisted
 * outcomes.
 */
final class LiveSkillChecks {
  private final JavaPlugin fixture;

  LiveSkillChecks(JavaPlugin fixture) {
    this.fixture = fixture;
  }

  void start() {
    Bukkit.getGlobalRegionScheduler()
        .runDelayed(
            fixture,
            t -> {
              World world = Bukkit.getWorlds().getFirst();
              world.setTime(1000);
              List<CompletableFuture<Void>> chunks = new ArrayList<>();
              for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++) {
                  int cx = x, cz = z;
                  CompletableFuture<Void> ready = new CompletableFuture<>();
                  chunks.add(ready);
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
              CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new))
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
  private void seed(World world) {
    try {
      CivilizationsPlugin plugin =
          (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
      int y = world.getHighestBlockYAt(0, 0) + 4;
      for (int x = -8; x <= 12; x++)
        for (int z = -6; z <= 6; z++)
          for (int dy = -1; dy <= 3; dy++)
            world
                .getBlockAt(x, y + dy, z)
                .setType(dy == -1 && (x < 3 || x > 4) ? Material.STONE : Material.AIR, false);
      world.getBlockAt(0, y, 2).setType(Material.LEAF_LITTER, false);
      Settlement.Data data = new Settlement.Data();
      data.world = world.getUID().toString();
      data.center = new Pos(2, y, 0);
      data.playerBlocks.add("8," + (y + 26) + ",0");
      world.getBlockAt(8, y + 26, 0).setType(Material.STONE, false);
      world.getBlockAt(8, y + 27, 0).setType(Material.LEAF_LITTER, false);
      Settlement village = new Settlement(data);
      var villages = CivilizationsPlugin.class.getDeclaredField("settlements");
      villages.setAccessible(true);
      ((Map<String, Settlement>) villages.get(plugin)).put(village.id(), village);
      Villager actor = world.spawn(new Location(world, 2.5, y, .5), Villager.class);
      actor.setAdult();
      // Register ownership for permission checks but let only this fixture tick the executor.
      village.enroll(actor.getUniqueId().toString(), 20);
      var ownership = new VillagerWorker(plugin, actor, village);
      var workerField = CivilizationsPlugin.class.getDeclaredField("workers");
      workerField.setAccessible(true);
      ((Map<String, VillagerWorker>) workerField.get(plugin))
          .put(actor.getUniqueId().toString(), ownership);
      actor.getInventory().addItem(new ItemStack(Material.DIRT, 2));
      Terrain terrain =
          new Terrain() {
            public int height(int x, int z) {
              return y - 1;
            }

            public boolean available(int x, int z) {
              return true;
            }

            public String type(Pos p) {
              return world.getBlockAt(p.x(), p.y(), p.z()).getType().name();
            }

            public String blockData(Pos p) {
              return world.getBlockAt(p.x(), p.y(), p.z()).getBlockData().getAsString();
            }
          };
      NavigationMap map = NavigationTerrain.capture(terrain, data.center, 8, Set.of());
      if (map.cell(new Pos(0, y, 2)).kind() != NavigationMap.Kind.AIR)
        throw new AssertionError("Leaf litter remains hazardous");
      var heightSurvey =
          plugin
              .navigation()
              .request(
                  world,
                  village,
                  "height-fixture",
                  data.center,
                  new Pos(8, y + 27, 0),
                  1,
                  "fixture-height");
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      var secondReport = new java.util.concurrent.atomic.AtomicReference<String>();
      String proposal =
          """
          {"explanation":"Fixture: bridge the two-block dry gap with actual dirt, then walk across.","steps":[
          {"op":"CLASSIFY","x":6,"y":27,"z":0,"material":"PASSABLE"},
          {"op":"PLACE_SUPPORT","x":1,"y":-1,"z":0,"material":"DIRT"},
          {"op":"WALK","x":1,"y":0,"z":0,"material":""},
          {"op":"PLACE_SUPPORT","x":2,"y":-1,"z":0,"material":"DIRT"},
          {"op":"WALK","x":4,"y":0,"z":0,"material":""},
          {"op":"VERIFY","x":0,"y":0,"z":0,"material":""}]}
          """;
      ModelBackend fake =
          new ModelBackend() {
            public boolean ready() {
              return true;
            }

            public String status() {
              return "fixture";
            }

            public void close() {}

            public String complete(String system, String report) {
              if (!report.contains("goal_relative") || !report.contains("DIRT"))
                throw new AssertionError("Missing observation");
              if (calls.incrementAndGet() == 1)
                return "{\"explanation\":\"Observe elevated obstruction without"
                           + " walking\",\"steps\":[{\"op\":\"OBSERVE\",\"x\":6,\"y\":27,\"z\":0,\"material\":\"\"}]}";
              secondReport.set(report);
              if (!report.contains("requested_observation")
                  || !report.contains("LEAF_LITTER")
                  || !report.contains("protected_or_reserved"))
                throw new IllegalStateException("Requested remote geometry missing");
              return proposal;
            }
          };
      InferenceQueue queue = new InferenceQueue(fake, 4);
      Path evidence =
          plugin
              .getDataFolder()
              .toPath()
              .resolve("CoreAI-observation-fixture-" + world.getUID() + "");
      RecoveryExperiments service =
          new RecoveryExperiments(
              evidence,
              queue,
              () -> "deterministic fixture; not a real model",
              fixture.getLogger()::warning);
      var field = CivilizationsPlugin.class.getDeclaredField("experiments");
      field.setAccessible(true);
      field.set(plugin, service);
      plugin.navigation().rules(service.rules());
      WorkMovementControl control =
          new WorkMovementControl(
              actor,
              message -> {
                throw new AssertionError(message);
              });
      control.working(true);
      java.util.concurrent.atomic.AtomicReference<Boolean> result =
          new java.util.concurrent.atomic.AtomicReference<>();
      WorkerSkillTrial skill =
          new WorkerSkillTrial(
              plugin,
              actor,
              village,
              (success, reason) -> {
                fixture.getLogger().info("SKILL RESULT: " + reason);
                result.set(success);
              });
      long started = System.currentTimeMillis();
      if (!skill.start(map, new Pos(6, y, 0), 1, "fixture_disconnected_floor", started))
        throw new AssertionError("No pilot admitted");
      actor
          .getScheduler()
          .runAtFixedRate(
              fixture,
              t -> {
                try {
                  if (plugin.worker(actor.getUniqueId().toString()) != ownership)
                    throw new AssertionError("Fixture actor has competing controllers");
                  control.working(true);
                  skill.tick(System.currentTimeMillis());
                  if (result.get() != null) {
                    var highMap = heightSurvey.join().map();
                    if (highMap.vertical < 30
                        || highMap.cell(new Pos(8, y + 27, 0)).kind() != NavigationMap.Kind.AIR
                        || !highMap
                            .cell(new Pos(8, y + 26, 0))
                            .removal()
                            .equals("protected_or_reserved")
                        || !highMap.cell(new Pos(-6, y - 1, -4)).removal().contains("mining"))
                      throw new AssertionError("Height capture or obstacle distinction failed");
                    if (calls.get() != 2 || secondReport.get().contains("failed_execution"))
                      throw new AssertionError("Observation incorrectly required route failure");
                    if (service
                            .rules()
                            .rule(
                                actor.getUniqueId().toString(),
                                "LEAF_LITTER",
                                world.getBlockAt(8, y + 27, 0).getBlockData().getAsString())
                        == null) throw new AssertionError("Remote classification missing");
                    if (!result.get()
                        || actor.getLocation().getX() < 5.5
                        || world.getBlockAt(3, y - 1, 0).getType() != Material.DIRT
                        || world.getBlockAt(4, y - 1, 0).getType() != Material.DIRT
                        || InventoryOps.count(actor.getInventory(), Material.DIRT) != 0)
                      throw new AssertionError(
                          "Actual movement, support blocks or ingredient conservation failed");
                    t.cancel();
                    control.working(false);
                    Bukkit.getAsyncScheduler()
                        .runDelayed(
                            fixture,
                            q -> {
                              try {
                                String saved =
                                    Files.readString(evidence.resolve("skills/skills.json"));
                                if (!saved.contains("verified_once"))
                                  throw new AssertionError("Observed success not persisted");
                                fixture
                                    .getLogger()
                                    .info(
                                        "OBSERVATION PASS: remote OBSERVE and CLASSIFY at +27y"
                                            + " without walking there; goal-height capture;"
                                            + " protected versus ordinary solid distinction; actual"
                                            + " two-block support placement, two dirt consumed,"
                                            + " villager crossed gap, original-goal arrival and"
                                            + " learned outcome persisted; leaf litter walkable."
                                            + " Proposal was deterministic fixture, not model"
                                            + " intelligence.");
                                service.close();
                                queue.close();
                                Bukkit.getGlobalRegionScheduler()
                                    .run(fixture, k -> Bukkit.shutdown());
                              } catch (Throwable e) {
                                fail(e);
                              }
                            },
                            1,
                            TimeUnit.SECONDS);
                  } else if (System.currentTimeMillis() - started > 60000)
                    throw new AssertionError("Physical skill timeout at " + actor.getLocation());
                } catch (Throwable e) {
                  t.cancel();
                  skill.cancel("fixture_failed");
                  control.working(false);
                  service.close();
                  queue.close();
                  fail(e);
                }
              },
              () -> {},
              1,
              5);
    } catch (Throwable e) {
      fail(e);
    }
  }

  private void fail(Throwable error) {
    fixture.getLogger().severe("OBSERVATION FAIL: " + error);
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> Bukkit.shutdown(), 20);
  }
}
