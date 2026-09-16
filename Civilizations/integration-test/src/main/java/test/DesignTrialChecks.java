package test;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import dev.civilizations.design.*;
import dev.civilizations.world.*;
import dev.coreai.TerrainRuleBook;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** One physical rejected-plan experiment, including actual learned-grass navigation. */
final class DesignTrialChecks {
  private final JavaPlugin fixture;
  private final List<VillagerWorker> workers = new ArrayList<>();
  private World world;
  private CivilizationsPlugin plugin;
  private boolean done;

  DesignTrialChecks(JavaPlugin fixture) {
    this.fixture = fixture;
  }

  void start() {
    Bukkit.getGlobalRegionScheduler()
        .runDelayed(
            fixture,
            t -> {
              world = Bukkit.getWorlds().getFirst();
              world.setTime(1000);
              var calls = new ArrayList<CompletableFuture<Void>>();
              for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++) {
                  int cx = x, cz = z;
                  var call = new CompletableFuture<Void>();
                  calls.add(call);
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
                                        call.complete(null);
                                      }))
                      .exceptionally(
                          error -> {
                            call.completeExceptionally(error);
                            return null;
                          });
                }
              CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new))
                  .thenRun(
                      () ->
                          Bukkit.getRegionScheduler()
                              .runDelayed(
                                  fixture, new Location(world, 0, 1, 0), task -> seed(), 60))
                  .exceptionally(
                      error -> {
                        fail(error);
                        return null;
                      });
            },
            60);
  }

  @SuppressWarnings("unchecked")
  private void seed() {
    try {
      plugin = (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
      int y = world.getHighestBlockYAt(0, 0) + 4;
      for (int x = -4; x <= 13; x++)
        for (int z = -4; z <= 14; z++)
          for (int h = -1; h <= 6; h++)
            world.getBlockAt(x, y + h, z).setType(h == -1 ? Material.DIRT : Material.AIR, false);
      // Grass under the actor reproduces the live CLEARABLE -> no_safe_start_cell failure.
      world.getBlockAt(0, y, 0).setType(Material.SHORT_GRASS, false);
      boolean preparation = Boolean.getBoolean("civilizations.test.site-preparation");
      if (preparation) {
        world.getBlockAt(3, y, 2).setType(Material.LEAF_LITTER, false);
        world.getBlockAt(3, y + 1, 2).setType(Material.DIRT, false);
        world.getBlockAt(6, y - 1, 4).setType(Material.COBBLESTONE, false);
        world.getBlockAt(6, y + 4, 4).setType(Material.DIRT, false);
        world.getBlockAt(2, y, 4).setType(Material.OAK_LOG, false);
        world.getBlockAt(2, y + 1, 4).setType(Material.OAK_LEAVES, false);
      }
      var data = new Settlement.Data();
      data.world = world.getUID().toString();
      data.center = new Pos(0, y, 0);
      var village = new Settlement(data);
      var settlements = CivilizationsPlugin.class.getDeclaredField("settlements");
      settlements.setAccessible(true);
      ((Map<String, Settlement>) settlements.get(plugin)).put(village.id(), village);
      var a = actor(village, .5, y, .5);
      actor(village, 11.5, y, 10.5);
      var first = workers.getFirst();
      a.getInventory()
          .addItem(new ItemStack(Material.COBBLESTONE, 32), new ItemStack(Material.OAK_FENCE_GATE));
      var rules =
          new TerrainRuleBook(
              fixture.getDataFolder().toPath().resolve(world.getName()).resolve("rules"));
      rules.stage(
          "grass",
          first.id(),
          BlockObservation.capture(world.getBlockAt(0, y, 0)),
          "CLEARABLE",
          "Actual removable grass",
          "fixture");
      plugin.navigation().rules(rules);
      var blueprint =
          new Blueprint(
              "wall",
              "Experimental rejected wall",
              4,
              2,
              0,
              0,
              1,
              "north",
              List.of(
                  new Blueprint.Point(2, 2),
                  new Blueprint.Point(6, 2),
                  new Blueprint.Point(6, 6),
                  new Blueprint.Point(2, 6)));
      var proposal = DesignProposals.retain(village, blueprint, village.center(), 0);
      try {
        DesignCompiler.validatePurpose(blueprint, village.center(), List.of());
        throw new AssertionError("Fixture wall was not rejected");
      } catch (IllegalArgumentException expected) {
        proposal = DesignProposals.defer(village, proposal, expected.getMessage(), Long.MAX_VALUE);
      }
      String project = DesignProposals.project(proposal);
      ProbeCommand.positions(plugin)
          .thenAccept(
              samples -> {
                var chosen =
                    WorkerPosition.nearest(
                            new WorkerPosition("player", world.getUID(), -30, y, 0),
                            samples.positions())
                        .orElseThrow();
                require(
                    chosen.worker().equals(first.id()),
                    "Nearest worker selection outside old 16-block range");
                require(samples.unavailable() == 0, "Entity scheduler samples unavailable");
                fixture
                    .getLogger()
                    .info(
                        "TRIAL NEAREST PASS: real worker positions selected from more than 16"
                            + " blocks away");
                Bukkit.getGlobalRegionScheduler()
                    .execute(
                        fixture,
                        () ->
                            Bukkit.dispatchCommand(
                                Bukkit.getConsoleSender(),
                                "civ debug probe trial " + first.id() + " auto 90"));
              })
          .exceptionally(
              error -> {
                fail(error);
                return null;
              });
      a.getScheduler()
          .runDelayed(
              fixture,
              task -> {
                a.setAI(true);
                first.start();
              },
              () -> {},
              100);
      long started = System.currentTimeMillis();
      a.getScheduler()
          .runAtFixedRate(
              fixture,
              task -> {
                try {
                  if (done) {
                    task.cancel();
                    return;
                  }
                  if (village.hasProject(project) && village.allComplete(project)) {
                    var jobs =
                        village.jobs().stream().filter(j -> j.project.equals(project)).toList();
                    long stone =
                        jobs.stream()
                            .filter(
                                j -> j.kind == Job.Kind.PLACE && j.material.equals("COBBLESTONE"))
                            .count();
                    require(
                        preparation
                            ? stone > 15 && jobs.stream().anyMatch(j -> j.kind == Job.Kind.CLEAR)
                            : jobs.size() == 16,
                        "Expected complete wall and preparation");
                    Map<Pos, String> finalBlocks = new HashMap<>();
                    for (Job j : jobs)
                      finalBlocks.put(
                          j.target,
                          j.kind == Job.Kind.CLEAR || j.kind == Job.Kind.MINE ? "AIR" : j.material);
                    for (var entry : finalBlocks.entrySet())
                      require(
                          world
                              .getBlockAt(
                                  entry.getKey().x(), entry.getKey().y(), entry.getKey().z())
                              .getType()
                              .name()
                              .equals(entry.getValue()),
                          "Missing actual " + entry.getValue() + " at " + entry.getKey());
                    require(
                        InventoryOps.count(a.getInventory(), Material.COBBLESTONE) == 32 - stone,
                        "Cobblestone conservation");
                    require(
                        InventoryOps.count(a.getInventory(), Material.OAK_FENCE_GATE) == 0,
                        "Gate conservation");
                    require(
                        world.getBlockAt(0, y, 0).getType() == Material.SHORT_GRASS,
                        "Walking wrongly required grass removal");
                    if (preparation) {
                      require(
                          InventoryOps.count(a.getInventory(), Material.DIRT) == 2,
                          "Cleared soil drops retained");
                      require(
                          InventoryOps.count(a.getInventory(), Material.OAK_LOG) == 1,
                          "Cleared tree drops retained");
                      fixture
                          .getLogger()
                          .info(
                              "SITE PREPARATION PASS: "
                                  + jobs.size()
                                  + " real jobs; "
                                  + stone
                                  + " paid cobblestone; two soil drops and one log retained;"
                                  + " complete wall over repaired foundations");
                    }
                    first.probe(
                        "status",
                        "auto",
                        1000,
                        lines -> {
                          try {
                            require(
                                lines.getFirst().contains(" PASS "),
                                "First action receipt not PASS: " + lines);
                            done = true;
                            workers.forEach(VillagerWorker::stop);
                            fixture
                                .getLogger()
                                .info(
                                    "DESIGN TRIAL PASS: rejected purpose -> explicit command ->"
                                        + " full 16-block wall/gate; exact inventory; learned grass"
                                        + " retained; first-action probe PASS; models disabled");
                            Bukkit.getGlobalRegionScheduler()
                                .runDelayed(fixture, ignored -> Bukkit.shutdown(), 20);
                          } catch (Throwable error) {
                            fail(error);
                          }
                        });
                    task.cancel();
                  } else if (System.currentTimeMillis() - started > 110000)
                    throw new AssertionError(
                        "Trial timed out: "
                            + first.inspection()
                            + " designs="
                            + village.designs()
                            + " proposals="
                            + village.proposals());
                } catch (Throwable error) {
                  task.cancel();
                  fail(error);
                }
              },
              () -> {},
              20,
              5);
    } catch (Throwable error) {
      fail(error);
    }
  }

  @SuppressWarnings("unchecked")
  private Villager actor(Settlement village, double x, int y, double z) throws Exception {
    var actor = world.spawn(new Location(world, x, y, z), Villager.class);
    actor.setAdult();
    actor.setPersistent(true);
    actor.setAI(false); // Hold the starting position until command admission and worker control.
    actor.getInventory().clear();
    village.enroll(actor.getUniqueId().toString(), 20);
    var worker = new VillagerWorker(plugin, actor, village);
    workers.add(worker);
    var field = CivilizationsPlugin.class.getDeclaredField("workers");
    field.setAccessible(true);
    ((Map<String, VillagerWorker>) field.get(plugin)).put(worker.id(), worker);
    return actor;
  }

  private static void require(boolean yes, String reason) {
    if (!yes) throw new AssertionError(reason);
  }

  private void fail(Throwable error) {
    if (done) return;
    done = true;
    fixture.getLogger().severe("DESIGN TRIAL FAIL: " + error);
    error.printStackTrace();
    workers.forEach(VillagerWorker::stop);
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> Bukkit.shutdown(), 20);
  }
}
