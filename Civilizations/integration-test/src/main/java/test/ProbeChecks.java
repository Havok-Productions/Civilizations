package test;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import dev.civilizations.world.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Commands ordinary workers, then checks physical effects and reported outcomes together. */
final class ProbeChecks {
  private final JavaPlugin fixture;
  private CivilizationsPlugin plugin;
  private World world;
  private int y;
  private final List<VillagerWorker> workers = new ArrayList<>();
  private final boolean[] verified = new boolean[2];

  ProbeChecks(JavaPlugin fixture) {
    this.fixture = fixture;
  }

  void start() {
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> prepare(), 60);
  }

  private void prepare() {
    world = Bukkit.getWorlds().getFirst();
    world.setTime(1000);
    var waits = new ArrayList<CompletableFuture<Void>>();
    for (int x = -1; x <= 1; x++)
      for (int z = -1; z <= 1; z++) {
        int cx = x, cz = z;
        var wait = new CompletableFuture<Void>();
        waits.add(wait);
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
                              wait.complete(null);
                            }))
            .exceptionally(
                error -> {
                  wait.completeExceptionally(error);
                  return null;
                });
      }
    CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new))
        .thenRun(
            () ->
                Bukkit.getRegionScheduler()
                    .runDelayed(fixture, new Location(world, 0, 1, 0), t -> seed(), 60))
        .exceptionally(
            error -> {
              fail(error);
              return null;
            });
  }

  @SuppressWarnings("unchecked")
  private void seed() {
    try {
      plugin = (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
      y = world.getHighestBlockYAt(0, 0) + 4;
      require(Bukkit.isOwnedByCurrentRegion(new Location(world, 0, y, 0), 1), "fixture region");
      for (int x = -4; x <= 13; x++)
        for (int z = -4; z <= 14; z++)
          for (int h = -1; h <= 7; h++)
            world.getBlockAt(x, y + h, z).setType(h == -1 ? Material.STONE : Material.AIR, false);
      world.getBlockAt(4, y - 1, 3).setType(Material.DIRT, false);
      for (int h = 0; h < 4; h++) world.getBlockAt(4, y + h, 3).setType(Material.OAK_LOG, false);
      world
          .getBlockAt(4, y + 4, 3)
          .setBlockData(
              Bukkit.createBlockData("minecraft:oak_leaves[distance=1,persistent=true]"), false);
      require(BlockRules.tree(world.getBlockAt(4, y, 3)), "seeded natural tree not recognized");
      var data = new Settlement.Data();
      data.world = world.getUID().toString();
      data.center = new Pos(0, y, 0);
      var village = new Settlement(data);
      var field = CivilizationsPlugin.class.getDeclaredField("settlements");
      field.setAccessible(true);
      ((Map<String, Settlement>) field.get(plugin)).put(village.id(), village);
      Job requested =
          new Job(
              Job.Kind.PLACE,
              "probe-build",
              new Pos(10, y, 0),
              new Pos(9, y, 0),
              "OAK_PLANKS",
              "AIR",
              null);
      Job other =
          new Job(
              Job.Kind.PLACE,
              "probe-other",
              new Pos(1, y, -2),
              new Pos(0, y, -2),
              "OAK_PLANKS",
              "AIR",
              null);
      Job blocked =
          new Job(
              Job.Kind.CLEAR,
              "probe-blocked",
              new Pos(2, y, 10),
              new Pos(1, y, 10),
              "",
              "DIRT",
              null);
      world.getBlockAt(2, y, 10).setType(Material.DIRT, false);
      village.playerPlaced(blocked.target);
      village.addProject(requested.project, List.of(requested));
      village.addProject(other.project, List.of(other));
      village.addProject(blocked.project, List.of(blocked));
      var a = actor(village, 0);
      var b = actor(village, 10);
      var first = workers.get(0);
      var second = workers.get(1);
      Bukkit.getGlobalRegionScheduler()
          .execute(
              fixture,
              () -> {
                Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(),
                    "civ debug probe start " + first.id() + " " + requested.id + " 45");
                Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(),
                    "civ debug probe start " + second.id() + " " + blocked.id + " 8");
              });
      a.getScheduler().runDelayed(fixture, t -> first.start(), () -> {}, 10);
      b.getScheduler().runDelayed(fixture, t -> second.start(), () -> {}, 10);
      long started = System.currentTimeMillis();
      a.getScheduler()
          .runAtFixedRate(
              fixture,
              task -> {
                try {
                  if (!verified[0]
                      && village.jobs().stream()
                          .anyMatch(j -> j.id.equals(requested.id) && j.complete)) {
                    require(
                        world.getBlockAt(10, y, 0).getType() == Material.OAK_PLANKS,
                        "placed block missing");
                    require(
                        world.getBlockAt(1, y, -2).getType() == Material.AIR,
                        "probe switched to easier unrelated job");
                    require(
                        world.getBlockAt(4, y + 3, 3).getType() == Material.AIR,
                        "tree not harvested");
                    require(
                        InventoryOps.count(a.getInventory(), Material.OAK_PLANKS) == 3,
                        "incorrect plank conservation");
                    first.probe(
                        "status",
                        "auto",
                        1000,
                        lines -> {
                          try {
                            require(
                                lines.getFirst().contains(" PASS "),
                                "physical build not reported PASS: " + lines);
                            verified[0] = true;
                            first.stop();
                            fixture
                                .getLogger()
                                .info(
                                    "PROBE BUILD PASS: command -> walk -> harvest -> craft ->"
                                        + " place; exact leftovers; selected task retained");
                          } catch (Throwable e) {
                            fail(e);
                          }
                        });
                  }
                  if (!verified[1] && System.currentTimeMillis() - started > 10000)
                    second.probe(
                        "status",
                        "auto",
                        1000,
                        lines -> {
                          try {
                            require(
                                lines.getFirst().contains(" TIMEOUT "),
                                "blocked probe not reported TIMEOUT: " + lines);
                            require(
                                lines.getFirst().contains("Work blocked by protection"),
                                "precise blocker absent: " + lines);
                            require(
                                world.getBlockAt(2, y, 10).getType() == Material.DIRT,
                                "protected block changed");
                            require(
                                village.jobs().stream()
                                    .noneMatch(j -> j.id.equals(blocked.id) && j.complete),
                                "blocked job falsely completed");
                            verified[1] = true;
                            second.stop();
                            fixture
                                .getLogger()
                                .info(
                                    "PROBE BLOCKED PASS: protection failure recorded; timed out"
                                        + " honestly; queued task and block retained");
                          } catch (Throwable e) {
                            fail(e);
                          }
                        });
                  if (verified[0] && verified[1]) {
                    task.cancel();
                    fixture
                        .getLogger()
                        .info(
                            "TASK PROBE PASS: real command/worker outcomes verified; models"
                                + " disabled");
                    Bukkit.getGlobalRegionScheduler()
                        .runDelayed(fixture, t -> Bukkit.shutdown(), 20);
                  } else if (System.currentTimeMillis() - started > 55000)
                    throw new AssertionError(
                        "probe deadline "
                            + first.inspection()
                            + " | tree="
                            + BlockRules.tree(world.getBlockAt(4, y, 3))
                            + " | soil="
                            + world.getBlockAt(4, y - 1, 3).getType()
                            + " | leaves="
                            + world.getBlockAt(4, y + 4, 3).getBlockData().getAsString());
                } catch (Throwable e) {
                  task.cancel();
                  fail(e);
                }
              },
              () -> {},
              20,
              5);
    } catch (Throwable e) {
      fail(e);
    }
  }

  @SuppressWarnings("unchecked")
  private Villager actor(Settlement village, int z) throws Exception {
    var a = world.spawn(new Location(world, .5, y, z + .5), Villager.class);
    a.setAdult();
    a.setPersistent(true);
    a.getInventory().clear();
    village.enroll(a.getUniqueId().toString(), 20);
    var worker = new VillagerWorker(plugin, a, village);
    workers.add(worker);
    var f = CivilizationsPlugin.class.getDeclaredField("workers");
    f.setAccessible(true);
    ((Map<String, VillagerWorker>) f.get(plugin)).put(worker.id(), worker);
    return a;
  }

  private void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private void fail(Throwable error) {
    fixture.getLogger().severe("TASK PROBE FAIL: " + error);
    error.printStackTrace();
    workers.forEach(VillagerWorker::stop);
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> Bukkit.shutdown(), 20);
  }
}
