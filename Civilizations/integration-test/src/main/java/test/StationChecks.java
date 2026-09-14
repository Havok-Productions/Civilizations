package test;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import dev.civilizations.world.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** The local-station regression without a preceding 62-block repair/navigation run. */
final class StationChecks {
  private final JavaPlugin fixture;

  StationChecks(JavaPlugin fixture) {
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
              for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++) {
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
                          error -> {
                            ready.completeExceptionally(error);
                            return null;
                          });
                }
              CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                  .orTimeout(20, TimeUnit.SECONDS)
                  .whenComplete(
                      (unused, error) -> {
                        if (error != null) {
                          fail(error);
                          return;
                        }
                        Bukkit.getRegionScheduler()
                            .runDelayed(
                                fixture, new Location(world, 0, 1, 0), task -> seed(world), 60);
                      });
            },
            60);
  }

  @SuppressWarnings("unchecked")
  private void seed(World world) {
    try {
      CivilizationsPlugin plugin =
          (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
      int y = world.getHighestBlockYAt(0, 0) + 1;
      Pos inaccessible = new Pos(8, y, 0);
      if (!Bukkit.isOwnedByCurrentRegion(new Location(world, 8, y, 0), 1))
        throw new AssertionError("Fixture region unavailable");
      Settlement.Data data = new Settlement.Data();
      data.world = world.getUID().toString();
      data.center = new Pos(0, y, 0);
      Settlement village = new Settlement(data);
      var field = CivilizationsPlugin.class.getDeclaredField("settlements");
      field.setAccessible(true);
      ((Map<String, Settlement>) field.get(plugin)).put(village.id(), village);
      Villager actor = world.spawn(new Location(world, .5, y, .5), Villager.class);
      actor.setAdult();
      village.enroll(actor.getUniqueId().toString(), 20);
      world.getBlockAt(8, y, 0).setType(Material.CRAFTING_TABLE, false);
      village.craftingTable(inaccessible);
      village
          .knowledge()
          .block(
              "route:" + inaccessible.key(),
              "fixture: route inaccessible",
              System.currentTimeMillis(),
              300_000);
      actor
          .getInventory()
          .addItem(new ItemStack(Material.OAK_LOG, 4), new ItemStack(Material.COBBLESTONE, 3));
      WorkerNavigation nav =
          new WorkerNavigation(
              plugin,
              actor,
              village,
              (now, reason) -> {
                throw new AssertionError(reason);
              });
      ToolActions tools =
          new ToolActions(
              plugin,
              actor,
              village,
              nav,
              (now, reason) -> {
                throw new AssertionError(reason);
              });
      WorkMovementControl control =
          new WorkMovementControl(
              actor,
              reason -> {
                throw new AssertionError(reason);
              });
      long started = System.currentTimeMillis();
      actor
          .getScheduler()
          .runAtFixedRate(
              fixture,
              t -> {
                try {
                  control.working(true);
                  Pos at =
                      new Pos(
                          actor.getLocation().getBlockX(),
                          actor.getLocation().getBlockY(),
                          actor.getLocation().getBlockZ());
                  if (tools.prepare(2, System.currentTimeMillis(), at).ready()) {
                    Pos local = village.craftingTable();
                    if (local == null
                        || local.equals(inaccessible)
                        || InventoryOps.count(actor.getInventory(), Material.STONE_PICKAXE) != 1
                        || InventoryOps.count(actor.getInventory(), Material.COBBLESTONE) != 0
                        || world.getBlockAt(local.x(), local.y(), local.z()).getType()
                            != Material.CRAFTING_TABLE)
                      throw new AssertionError(
                          "No verified local station/tool with consumed ingredients");
                    fixture
                        .getLogger()
                        .info(
                            "LOCAL STATION PASS: inaccessible shared table skipped; local table"
                                + " placed; actual cobblestone consumed into stone pickaxe");
                    control.working(false);
                    t.cancel();
                    Bukkit.getGlobalRegionScheduler()
                        .runDelayed(fixture, q -> Bukkit.shutdown(), 20);
                  } else if (System.currentTimeMillis() - started > 25_000)
                    throw new AssertionError("Local crafting deadline");
                } catch (Throwable error) {
                  control.working(false);
                  t.cancel();
                  fail(error);
                }
              },
              () -> {},
              5,
              5);
    } catch (Throwable error) {
      fail(error);
    }
  }

  private void fail(Throwable error) {
    fixture.getLogger().severe("LOCAL STATION FAIL: " + error);
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> Bukkit.shutdown(), 20);
  }
}
