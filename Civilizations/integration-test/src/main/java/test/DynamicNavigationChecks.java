package test;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import dev.civilizations.learning.RecoveryExperiments;
import dev.civilizations.world.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * One short physical regression: unavailable proposal, door, growth after mapping, retained wood.
 */
final class DynamicNavigationChecks {
  private final JavaPlugin fixture;
  private World world;
  private boolean callback, grown, remapped;

  DynamicNavigationChecks(JavaPlugin fixture) {
    this.fixture = fixture;
  }

  void start() {
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> prepare(), 60);
  }

  void prepare() {
    world = Bukkit.getWorlds().getFirst();
    world.setTime(1000);
    List<CompletableFuture<Void>> loaded = new ArrayList<>();
    for (int x = -2; x <= 2; x++)
      for (int z = -2; z <= 2; z++) {
        int cx = x, cz = z;
        var future = new CompletableFuture<Void>();
        loaded.add(future);
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
                              future.complete(null);
                            }))
            .exceptionally(
                error -> {
                  future.completeExceptionally(error);
                  return null;
                });
      }
    CompletableFuture.allOf(loaded.toArray(CompletableFuture[]::new))
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

  void seed() {
    try {
      var plugin = (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
      int y = world.getHighestBlockYAt(0, 0) + 1;
      if (!Bukkit.isOwnedByCurrentRegion(new Location(world, 12, y, 3), 2))
        throw new AssertionError("Fixture region unavailable");
      for (int x = -2; x <= 12; x++)
        for (int z = -1; z <= 1; z++) {
          world.getBlockAt(x, y - 1, z).setType(Material.DIRT, false);
          for (int h = 0; h < 5; h++)
            world
                .getBlockAt(x, y + h, z)
                .setType(z != 0 || x == -2 || x == 12 ? Material.STONE : Material.AIR, false);
        }
      for (int h = 0; h < 2; h++) {
        Door door = (Door) Material.OAK_DOOR.createBlockData();
        door.setFacing(org.bukkit.block.BlockFace.EAST);
        door.setHalf(h == 0 ? Bisected.Half.BOTTOM : Bisected.Half.TOP);
        door.setOpen(false);
        world.getBlockAt(2, y + h, 0).setBlockData(door, false);
      }
      Settlement.Data data = new Settlement.Data();
      data.world = world.getUID().toString();
      data.center = new Pos(0, y, 0);
      Settlement village = new Settlement(data);
      Villager actor = world.spawn(new Location(world, .5, y, .5), Villager.class);
      actor.setAdult();
      actor.getInventory().addItem(new ItemStack(Material.OAK_LOG, 3));
      if (plugin.mayChange(actor, world.getBlockAt(2, y, 0), "OPEN_GATE"))
        throw new AssertionError("Unenrolled worker unexpectedly admitted");
      var villagesField = CivilizationsPlugin.class.getDeclaredField("settlements");
      villagesField.setAccessible(true);
      @SuppressWarnings("unchecked")
      var villages = (Map<String, Settlement>) villagesField.get(plugin);
      villages.put(village.id(), village);
      village.enroll(actor.getUniqueId().toString(), 20);
      Terrain live =
          new Terrain() {
            public int height(int x, int z) {
              return world.getHighestBlockYAt(x, z);
            }

            public boolean available(int x, int z) {
              return Bukkit.isOwnedByCurrentRegion(new Location(world, x, y, z));
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
          };
      var map = NavigationTerrain.capture(live, data.center, 10, Set.of());
      // Routine fixture config disables adaptation. Install only the recovery service under test;
      // its existing disabled inference backend starts no model or network request.
      if (plugin.experiments() == null) {
        var experiments =
            new RecoveryExperiments(
                fixture.getDataFolder().toPath().resolve("dynamic-recovery-" + world.getUID()),
                plugin.inference(),
                () -> "fixture-disabled-model",
                fixture.getLogger()::warning);
        var serviceField = CivilizationsPlugin.class.getDeclaredField("experiments");
        serviceField.setAccessible(true);
        serviceField.set(plugin, experiments);
      }
      var trial =
          new WorkerSkillTrial(
              plugin,
              actor,
              village,
              (success, reason) -> {
                if (success || !reason.contains("proposal_rejected_or_unavailable"))
                  throw new AssertionError(reason);
                callback = true;
              });
      if (!trial.start(
          map, new Pos(10, y, 0), 0, "fixture_unavailable_proposal", System.currentTimeMillis()))
        throw new AssertionError("Recovery trial not admitted");
      var field = WorkerSkillTrial.class.getDeclaredField("trial");
      field.setAccessible(true);
      ((RecoveryExperiments.Trial) field.get(trial))
          .program.completeExceptionally(new IllegalStateException("fixture_model_unavailable"));
      trial.tick(System.currentTimeMillis());
      if (!callback || trial.active())
        throw new AssertionError("Failed proposal did not complete recovery callback");
      var control =
          new WorkMovementControl(
              actor,
              message -> {
                throw new AssertionError(message);
              });
      var navigation =
          new WorkerNavigation(
              plugin,
              actor,
              village,
              (time, reason) -> {
                throw new AssertionError(reason);
              });
      long started = System.currentTimeMillis();
      Location[] previous = {actor.getLocation().clone()};
      double[] walked = {0};
      actor
          .getScheduler()
          .runAtFixedRate(
              fixture,
              task -> {
                try {
                  long now = System.currentTimeMillis();
                  control.working(true);
                  Location current = actor.getLocation();
                  double distance = current.distance(previous[0]);
                  if (distance > 2)
                    throw new AssertionError("Unexpected position jump " + distance);
                  walked[0] += distance;
                  previous[0] = current.clone();
                  if (!grown && navigation.observedMap() != null) {
                    if (!navigation.observedMap().cell(new Pos(6, y, 0)).material().equals("AIR"))
                      throw new AssertionError("Initial map already had the tree");
                    for (int h = 0; h < 3; h++)
                      world.getBlockAt(6, y + h, 0).setType(Material.OAK_LOG, false);
                    Leaves leaves = (Leaves) Material.OAK_LEAVES.createBlockData();
                    leaves.setPersistent(false);
                    leaves.setDistance(1);
                    world.getBlockAt(6, y + 3, 0).setBlockData(leaves, false);
                    world.getBlockAt(5, y, 0).setType(Material.SHORT_GRASS, false);
                    grown = true;
                    fixture
                        .getLogger()
                        .info("DYNAMIC ROUTE: tree and grass added after initial map");
                  }
                  navigation.walkExact(new Pos(10, y, 0), 0, now);
                  remapped |= "route_terrain_changed".equals(navigation.evidence().get("reason"));
                  if (current.getBlockX() == 10
                      && current.getBlockY() == y
                      && current.getBlockZ() == 0) {
                    if (!grown || !remapped || walked[0] < 8)
                      throw new AssertionError("Growth/remap/physical travel was not exercised");
                    if (InventoryOps.count(actor.getInventory(), Material.OAK_LOG) != 5)
                      throw new AssertionError(
                          "Two cut logs not retained: "
                              + InventoryOps.summary(actor.getInventory()));
                    if (!((Door) world.getBlockAt(2, y, 0).getBlockData()).isOpen())
                      throw new AssertionError("Door was not opened");
                    if (world.getBlockAt(6, y, 1).getType() != Material.STONE)
                      throw new AssertionError("Corridor wall damaged");
                    fixture
                        .getLogger()
                        .info(
                            "DYNAMIC NAVIGATION PASS: unavailable proposal callback completed; real"
                                + " door opened; stale AIR map replaced after tree/grass growth;"
                                + " villager walked "
                                + walked[0]
                                + " blocks; cleared two natural logs; retained exactly five logs"
                                + " (three original plus two harvested); corridor preserved");
                    navigation.stop();
                    control.working(false);
                    task.cancel();
                    Bukkit.getGlobalRegionScheduler()
                        .runDelayed(fixture, t -> Bukkit.shutdown(), 20);
                  } else if (now - started > 90000)
                    throw new AssertionError("Dynamic route deadline: " + navigation.evidence());
                } catch (Throwable error) {
                  task.cancel();
                  fail(error);
                }
              },
              () -> {},
              1,
              1);
    } catch (Throwable error) {
      fail(error);
    }
  }

  void fail(Throwable error) {
    fixture.getLogger().severe("DYNAMIC NAVIGATION FAIL: " + error);
    error.printStackTrace();
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> Bukkit.shutdown(), 20);
  }
}
