package test;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.ai.*;
import dev.civilizations.core.*;
import dev.civilizations.learning.*;
import dev.civilizations.navigation.*;
import dev.civilizations.world.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.bukkit.*;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** One physical case for expanding recovery observations and sand-to-glass production. */
final class ProgressChecks {
  private final JavaPlugin fixture;
  private final int x = -4163, z = -1345;

  ProgressChecks(JavaPlugin fixture) {
    this.fixture = fixture;
  }

  void start() {
    Bukkit.getGlobalRegionScheduler()
        .runDelayed(
            fixture,
            t -> {
              World world = Bukkit.getWorlds().getFirst();
              world.setTime(1000);
              List<CompletableFuture<Void>> ready = new ArrayList<>();
              for (int cx = (x >> 4) - 1; cx <= (x >> 4) + 3; cx++)
                for (int cz = (z >> 4) - 1; cz <= (z >> 4) + 1; cz++) {
                  int xx = cx, zz = cz;
                  CompletableFuture<Void> loaded = new CompletableFuture<>();
                  ready.add(loaded);
                  world
                      .getChunkAtAsync(cx, cz, true)
                      .thenAccept(
                          c ->
                              Bukkit.getRegionScheduler()
                                  .execute(
                                      fixture,
                                      world,
                                      xx,
                                      zz,
                                      () -> {
                                        c.addPluginChunkTicket(fixture);
                                        loaded.complete(null);
                                      }))
                      .exceptionally(
                          e -> {
                            loaded.completeExceptionally(e);
                            return null;
                          });
                }
              CompletableFuture.allOf(ready.toArray(CompletableFuture[]::new))
                  .orTimeout(30, TimeUnit.SECONDS)
                  .whenComplete(
                      (unused, error) -> {
                        if (error != null) fail(error);
                        else
                          Bukkit.getRegionScheduler()
                              .runDelayed(
                                  fixture, new Location(world, x, 80, z), q -> seed(world), 60);
                      });
            },
            60);
  }

  private void seed(World world) {
    try {
      CivilizationsPlugin plugin =
          (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
      int y = world.getHighestBlockYAt(x, z) + 5;
      for (int dx = -6; dx <= 38; dx++)
        for (int dz = -6; dz <= 6; dz++)
          for (int dy = -2; dy <= 5; dy++)
            world
                .getBlockAt(x + dx, y + dy, z + dz)
                .setType(dy < 0 ? Material.STONE : Material.AIR, false);
      Pos origin = new Pos(x, y, z),
          obstacle = origin.add(24, 0, 2),
          glass = origin.add(27, 0, 0),
          sand = origin.add(24, -1, -3);
      world.getBlockAt(obstacle.x(), obstacle.y(), obstacle.z()).setType(Material.DIRT, false);
      world.getBlockAt(sand.x(), sand.y(), sand.z()).setType(Material.SAND, false);
      world.getBlockAt(sand.x(), sand.y() - 1, sand.z()).setType(Material.WATER, false);
      Pos table = origin.add(20, 0, -3);
      world.getBlockAt(table.x(), table.y(), table.z()).setType(Material.CRAFTING_TABLE, false);
      Settlement.Data data = new Settlement.Data();
      data.world = world.getUID().toString();
      data.center = origin;
      Settlement village = new Settlement(data);
      var villageField = CivilizationsPlugin.class.getDeclaredField("settlements");
      villageField.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, Settlement> villages = (Map<String, Settlement>) villageField.get(plugin);
      villages.put(village.id(), village);
      village.craftingTable(table);
      Villager actor = world.spawn(new Location(world, x + .5, y, z + .5), Villager.class);
      actor.setAdult();
      village.enroll(actor.getUniqueId().toString(), 20);
      if (Boolean.getBoolean("civilizations.test.farm-preparation")) {
        farm(plugin, world, actor, village, origin);
        return;
      }
      var control =
          new WorkMovementControl(
              actor,
              m -> {
                throw new AssertionError(m);
              });
      control.working(true);
      String proposal =
          """
          {"explanation":"Observe beyond the original map, walk there, and clear the dirt from the work area.","steps":[
          {"op":"SEARCH","x":32,"y":0,"z":0,"material":""},
          {"op":"WALK","x":22,"y":0,"z":0,"material":""},
          {"op":"CLEAR","x":24,"y":0,"z":2,"material":""}]}
          """;
      AtomicInteger calls = new AtomicInteger();
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
              calls.incrementAndGet();
              return proposal;
            }
          };
      InferenceQueue queue = new InferenceQueue(fake, 4);
      RecoveryExperiments experiments =
          new RecoveryExperiments(
              plugin.getDataFolder().toPath().resolve("CoreAI-progress-fixture-" + world.getUID()),
              queue,
              () -> "deterministic fixture",
              fixture.getLogger()::warning);
      var ef = CivilizationsPlugin.class.getDeclaredField("experiments");
      ef.setAccessible(true);
      ef.set(plugin, experiments);
      plugin.navigation().rules(experiments.rules());
      AtomicReference<String> outcome = new AtomicReference<>();
      WorkerSkillTrial trial =
          new WorkerSkillTrial(
              plugin,
              actor,
              village,
              (ok, reason) -> outcome.set((ok ? "success:" : "failure:") + reason));
      // Capture through the real Folia scheduler at the user's negative coordinate, not a fake map.
      RegionSnapshots snapshots = new RegionSnapshots(fixture, ForkJoinPool.commonPool());
      snapshots
          .capture(world, origin, 4)
          .whenComplete(
              (terrain, error) ->
                  actor
                      .getScheduler()
                      .run(
                          fixture,
                          t -> {
                            if (error != null) {
                              fail(error);
                              return;
                            }
                            try {
                              if (!terrain.available(x, z))
                                throw new AssertionError(
                                    "Loaded origin is missing: " + terrain.observationReport());
                              NavigationMap map =
                                  NavigationTerrain.capture(terrain, origin, 4, Set.of());
                              if (map.contains(obstacle))
                                throw new AssertionError("Fixture must begin outside recovery map");
                              if (!trial.startSite(
                                  map,
                                  obstacle,
                                  "fixture_instruction_outside_original_map",
                                  System.currentTimeMillis()))
                                throw new AssertionError("Trial unavailable");
                              long deadline = System.currentTimeMillis() + 90000;
                              actor
                                  .getScheduler()
                                  .runAtFixedRate(
                                      fixture,
                                      tick -> {
                                        try {
                                          control.working(true);
                                          trial.tick(System.currentTimeMillis());
                                          if (outcome.get() != null) {
                                            tick.cancel();
                                            if (!outcome.get().startsWith("success")
                                                || world
                                                        .getBlockAt(
                                                            obstacle.x(),
                                                            obstacle.y(),
                                                            obstacle.z())
                                                        .getType()
                                                    != Material.AIR
                                                || InventoryOps.count(
                                                        actor.getInventory(), Material.DIRT)
                                                    != 1
                                                || actor.getLocation().getBlockX() < x + 21)
                                              throw new AssertionError(
                                                  "Recovery failed: "
                                                      + outcome.get()
                                                      + " position="
                                                      + actor.getLocation());
                                            fixture
                                                .getLogger()
                                                .info(
                                                    "RECOVERY EXTENSION PASS: loaded"
                                                        + " negative-coordinate origin observed;"
                                                        + " walked beyond radius4; SEARCH32; actual"
                                                        + " dirt cleared and retained");
                                            control.working(false);
                                            craft(
                                                plugin,
                                                world,
                                                actor,
                                                village,
                                                glass,
                                                sand,
                                                calls,
                                                experiments,
                                                queue);
                                          } else if (System.currentTimeMillis() > deadline)
                                            throw new AssertionError(
                                                "Recovery timeout: " + trial.status());
                                        } catch (Throwable failure) {
                                          tick.cancel();
                                          fail(failure);
                                        }
                                      },
                                      () -> fail(new AssertionError("Recovery actor retired")),
                                      1,
                                      5);
                            } catch (Throwable failure) {
                              fail(failure);
                            }
                          },
                          () -> {}));
    } catch (Throwable failure) {
      fail(failure);
    }
  }

  private void craft(
      CivilizationsPlugin plugin,
      World world,
      Villager actor,
      Settlement village,
      Pos glass,
      Pos sand,
      AtomicInteger calls,
      RecoveryExperiments experiments,
      InferenceQueue queue) {
    actor
        .getInventory()
        .addItem(
            new ItemStack(Material.COBBLESTONE, 8),
            new ItemStack(Material.OAK_LOG, 2),
            new ItemStack(Material.WOODEN_PICKAXE, 1));
    Job job =
        new Job(Job.Kind.PLACE, "glass-repair", glass, glass.add(-1, 0, 0), "GLASS", "AIR", null);
    village.addProject("glass-repair", List.of(job));
    VillagerWorker worker = new VillagerWorker(plugin, actor, village);
    worker.start();
    long deadline = System.currentTimeMillis() + 150000;
    actor
        .getScheduler()
        .runAtFixedRate(
            fixture,
            t -> {
              try {
                if (world.getBlockAt(glass.x(), glass.y(), glass.z()).getType() == Material.GLASS
                    && job.complete) {
                  worker.stop();
                  t.cancel();
                  long furnaces =
                      village.repairBlocks().stream()
                          .filter(b -> b.material().equals("FURNACE"))
                          .count();
                  if (furnaces != 1
                      || InventoryOps.count(actor.getInventory(), Material.COBBLESTONE) != 0
                      || InventoryOps.count(actor.getInventory(), Material.OAK_LOG) != 1
                      || InventoryOps.count(actor.getInventory(), Material.DIRT) != 1
                      || InventoryOps.count(actor.getInventory(), Material.GLASS) != 0
                      || world.getBlockAt(sand.x(), sand.y(), sand.z()).getType() != Material.AIR
                      || calls.get() != 1)
                    throw new AssertionError(
                        "Glass material conservation failed: "
                            + InventoryOps.summary(actor.getInventory())
                            + " furnaces="
                            + furnaces
                            + " modelCalls="
                            + calls);
                  fixture
                      .getLogger()
                      .info(
                          "PROGRESS PASS: expanded recovery map and retained cleared dirt; gathered"
                              + " dry-bank sand above water; crafted and placed one real furnace;"
                              + " native fuel/cook cycle made glass; repaired target; eight"
                              + " cobblestone and one log consumed; one deterministic recovery"
                              + " request");
                  torch(plugin, world, actor, village, glass.add(3, 0, 0), experiments, queue);
                } else if (System.currentTimeMillis() > deadline)
                  throw new AssertionError(
                      "Glass workflow timeout: "
                          + worker.status()
                          + " inv="
                          + InventoryOps.summary(actor.getInventory()));
              } catch (Throwable failure) {
                t.cancel();
                worker.stop();
                fail(failure);
              }
            },
            () -> fail(new AssertionError("Crafting actor retired")),
            1,
            20);
  }

  private void fail(Throwable error) {
    error.printStackTrace();
    fixture.getLogger().severe("PROGRESS FAIL: " + error);
    Bukkit.getGlobalRegionScheduler().execute(fixture, Bukkit::shutdown);
  }

  private void torch(
      CivilizationsPlugin plugin,
      World world,
      Villager actor,
      Settlement village,
      Pos target,
      RecoveryExperiments experiments,
      InferenceQueue queue) {
    actor
        .getInventory()
        .addItem(new ItemStack(Material.OAK_PLANKS, 1), new ItemStack(Material.STICK, 1));
    Job job =
        new Job(
            Job.Kind.PLACE, "charcoal-torch", target, target.add(-1, 0, 0), "TORCH", "AIR", null);
    village.addProject(job.project, List.of(job));
    VillagerWorker worker = new VillagerWorker(plugin, actor, village);
    worker.start();
    long deadline = System.currentTimeMillis() + 90000;
    actor
        .getScheduler()
        .runAtFixedRate(
            fixture,
            tick -> {
              try {
                if (job.complete) {
                  worker.stop();
                  tick.cancel();
                  var inventory = InventoryOps.summary(actor.getInventory());
                  if (world.getBlockAt(target.x(), target.y(), target.z()).getType()
                          != Material.TORCH
                      || inventory.getOrDefault("TORCH", 0) != 3
                      || inventory.getOrDefault("CHARCOAL", 0) != 0
                      || inventory.getOrDefault("OAK_LOG", 0) != 0
                      || inventory.getOrDefault("STICK", 0) != 0
                      || inventory.getOrDefault("OAK_PLANKS", 0) != 0
                      || inventory.getOrDefault("DIRT", 0) != 1)
                    throw new AssertionError(
                        "Charcoal prerequisite was lost or not conserved: " + inventory);
                  fixture
                      .getLogger()
                      .info(
                          "CHARCOAL TORCH PASS: finished intermediate charcoal batch, supplied real"
                              + " fuel, crafted four torches and placed one; retained three torches"
                              + " and recovered dirt. Missing model VERIFY was supplied and checked"
                              + " by host.");
                  experiments.close();
                  queue.close();
                  Bukkit.getGlobalRegionScheduler().execute(fixture, Bukkit::shutdown);
                } else if (System.currentTimeMillis() > deadline)
                  throw new AssertionError(
                      "Charcoal prerequisite timeout: "
                          + worker.status()
                          + " inventory="
                          + InventoryOps.summary(actor.getInventory()));
              } catch (Throwable failure) {
                tick.cancel();
                worker.stop();
                fail(failure);
              }
            },
            () -> fail(new AssertionError("Torch actor retired")),
            1,
            10);
  }

  private void farm(
      CivilizationsPlugin plugin, World world, Villager actor, Settlement village, Pos origin) {
    Pos plot = origin.add(2, 0, 2);
    world.getBlockAt(plot.x(), plot.y() - 1, plot.z()).setType(Material.DIRT, false);
    world.getBlockAt(plot.x() + 2, plot.y() - 1, plot.z()).setType(Material.WATER, false);
    world.getBlockAt(plot.x(), plot.y(), plot.z()).setType(Material.DANDELION, false);
    actor.getInventory().addItem(new ItemStack(Material.WHEAT_SEEDS, 1));
    Job job =
        new Job(Job.Kind.FARM, "cluttered-farm", plot, plot.add(-1, 0, 0), "WHEAT", "AIR", null);
    village.addProject("cluttered-farm", List.of(job));
    VillagerWorker worker = new VillagerWorker(plugin, actor, village);
    worker.start();
    long deadline = System.currentTimeMillis() + 45000;
    actor
        .getScheduler()
        .runAtFixedRate(
            fixture,
            t -> {
              try {
                if (job.complete) {
                  worker.stop();
                  t.cancel();
                  if (world.getBlockAt(plot.x(), plot.y(), plot.z()).getType() != Material.WHEAT
                      || world.getBlockAt(plot.x(), plot.y() - 1, plot.z()).getType()
                          != Material.FARMLAND
                      || InventoryOps.count(actor.getInventory(), Material.WHEAT_SEEDS) != 0
                      || InventoryOps.count(actor.getInventory(), Material.DANDELION) != 1)
                    throw new AssertionError(
                        "Farm preparation did not retain flower or consume seed: "
                            + InventoryOps.summary(actor.getInventory()));
                  fixture
                      .getLogger()
                      .info(
                          "FARM PREPARATION PASS: real worker cleared dandelion, retained its drop,"
                              + " tilled hydrated dirt and planted wheat using one seed");
                  Bukkit.getGlobalRegionScheduler().execute(fixture, Bukkit::shutdown);
                } else if (System.currentTimeMillis() > deadline)
                  throw new AssertionError("Farm preparation timeout: " + worker.status());
              } catch (Throwable error) {
                t.cancel();
                worker.stop();
                fail(error);
              }
            },
            () -> fail(new AssertionError("Farm actor retired")),
            1,
            5);
  }
}
