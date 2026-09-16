package test;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import dev.civilizations.world.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.java.JavaPlugin;

/** One actual wet-source rejection, safe alternative harvest, and retry after water removal. */
final class SourceRecoveryChecks {
  private final JavaPlugin fixture;
  private World world;

  SourceRecoveryChecks(JavaPlugin fixture) {
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
      var plugin = (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
      int y = world.getHighestBlockYAt(0, 0) + 4;
      require(Bukkit.isOwnedByCurrentRegion(new Location(world, 0, y, 0), 1), "fixture ownership");
      for (int x = -4; x <= 5; x++)
        for (int z = -3; z <= 4; z++)
          for (int h = -1; h <= 3; h++)
            world.getBlockAt(x, y + h, z).setType(h == -1 ? Material.STONE : Material.AIR, false);
      Pos wet = new Pos(1, y, 0), dry = new Pos(3, y, 0);
      world.getBlockAt(wet.x(), y, wet.z()).setType(Material.SAND, false);
      world.getBlockAt(dry.x(), y, dry.z()).setType(Material.SAND, false);
      for (int[] p : new int[][] {{0, 1}, {2, 1}, {1, 2}})
        world.getBlockAt(p[0], y, p[1]).setType(Material.STONE, false);
      world.getBlockAt(1, y, 1).setType(Material.WATER, false);
      var data = new Settlement.Data();
      data.world = world.getUID().toString();
      data.center = new Pos(0, y, 0);
      var village = new Settlement(data);
      var vf = CivilizationsPlugin.class.getDeclaredField("settlements");
      vf.setAccessible(true);
      ((Map<String, Settlement>) vf.get(plugin)).put(village.id(), village);
      var actor = world.spawn(new Location(world, .5, y, .5), Villager.class);
      actor.setAdult();
      actor.setPersistent(true);
      actor.getInventory().clear();
      village.enroll(actor.getUniqueId().toString(), 20);
      var worker = new VillagerWorker(plugin, actor, village);
      var wf = CivilizationsPlugin.class.getDeclaredField("workers");
      wf.setAccessible(true);
      ((Map<String, VillagerWorker>) wf.get(plugin)).put(worker.id(), worker);
      var control =
          new WorkMovementControl(
              actor,
              r -> {
                throw new AssertionError(r);
              });
      control.working(true);
      var nav =
          new WorkerNavigation(
              plugin,
              actor,
              village,
              (now, r) -> {
                throw new AssertionError(r);
              });
      var gather =
          new GatheringActions(
              plugin,
              actor,
              village,
              nav,
              new RecoveryPolicy(System.currentTimeMillis(), 60000),
              (now, r) -> {
                throw new AssertionError(r);
              });
      long start = System.currentTimeMillis();
      var changed = new boolean[] {false};
      fixture.getLogger().info("SOURCE RECOVERY START worker=" + worker.id());
      actor
          .getScheduler()
          .runAtFixedRate(
              fixture,
              task -> {
                try {
                  long now = System.currentTimeMillis();
                  var at = actor.getLocation();
                  gather.gather(
                      "SAND", now, new Pos(at.getBlockX(), at.getBlockY(), at.getBlockZ()));
                  int held = InventoryOps.count(actor.getInventory(), Material.SAND);
                  if (!changed[0] && now - start >= 6500) {
                    require(held == 1, "safe alternative not harvested: held=" + held);
                    require(
                        world.getBlockAt(wet.x(), y, wet.z()).getType() == Material.SAND,
                        "wet source modified");
                    require(
                        world.getBlockAt(dry.x(), y, dry.z()).getType() == Material.AIR,
                        "dry source retained");
                    world.getBlockAt(1, y, 1).setType(Material.AIR, false);
                    gather.reset();
                    changed[0] = true;
                  }
                  if (changed[0] && held == 2) {
                    require(
                        world.getBlockAt(wet.x(), y, wet.z()).getType() == Material.AIR,
                        "changed source not harvested");
                    task.cancel();
                    nav.stop();
                    control.working(false);
                    fixture
                        .getLogger()
                        .info(
                            "SOURCE RECOVERY PASS: wet source retained through repeated scans, dry"
                                + " alternative harvested, changed source harvested without"
                                + " cooldown; exact 2 sand");
                    Bukkit.getGlobalRegionScheduler()
                        .runDelayed(fixture, t -> Bukkit.shutdown(), 20);
                  } else if (now - start > 15000)
                    throw new AssertionError("source retry deadline: held=" + held);
                } catch (Throwable error) {
                  task.cancel();
                  fail(error);
                }
              },
              () -> {},
              1,
              5);
    } catch (Throwable error) {
      fail(error);
    }
  }

  private void require(boolean test, String reason) {
    if (!test) throw new AssertionError(reason);
  }

  private void fail(Throwable error) {
    fixture.getLogger().severe("SOURCE RECOVERY FAIL: " + error);
    error.printStackTrace();
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> Bukkit.shutdown(), 20);
  }
}
