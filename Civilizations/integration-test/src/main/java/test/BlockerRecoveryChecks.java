package test;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import dev.civilizations.world.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Targeted alpha32 executors: capacity, abandoned cooking, occupied station, upper trunk. */
final class BlockerRecoveryChecks {
  private final JavaPlugin fixture;
  private CivilizationsPlugin plugin;
  private World world;
  private int y;
  private VillagerWorker builder;
  private final List<WorkMovementControl> controls = new ArrayList<>();

  BlockerRecoveryChecks(JavaPlugin fixture) {
    this.fixture = fixture;
  }

  void start() {
    Bukkit.getGlobalRegionScheduler()
        .runDelayed(
            fixture,
            task -> {
              world = Bukkit.getWorlds().getFirst();
              world.setTime(1000);
              var pending = new ArrayList<CompletableFuture<Void>>();
              for (int x = -2; x <= 4; x++)
                for (int z = -2; z <= 2; z++) {
                  int cx = x, cz = z;
                  var ready = new CompletableFuture<Void>();
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
                  .orTimeout(25, TimeUnit.SECONDS)
                  .whenComplete(
                      (done, error) -> {
                        if (error != null) fail(error);
                        else
                          Bukkit.getRegionScheduler()
                              .runDelayed(fixture, new Location(world, 0, 1, 0), t -> seed(), 60);
                      });
            },
            60);
  }

  @SuppressWarnings("unchecked")
  private void seed() {
    try {
      plugin = (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
      y = world.getHighestBlockYAt(0, 0) + 4;
      require(Bukkit.isOwnedByCurrentRegion(new Location(world, 36, y, 0), 1), "fixture ownership");
      for (int x = -4; x <= 43; x++)
        for (int z = -4; z <= 6; z++)
          for (int dy = -1; dy <= 7; dy++)
            block(x, dy, z).setType(dy == -1 ? Material.STONE : Material.AIR, false);
      var capacity = village(0);
      var crafter = actor(0, capacity);
      Material[] supplies = {
        Material.OAK_LOG,
        Material.GRANITE,
        Material.DIORITE,
        Material.ANDESITE,
        Material.TUFF,
        Material.CALCITE,
        Material.GRAVEL,
        Material.STONE
      };
      for (int i = 0; i < 8; i++) crafter.getInventory().setItem(i, new ItemStack(supplies[i], 64));
      Job job =
          new Job(
              Job.Kind.PLACE,
              "capacity",
              new Pos(2, y, 0),
              new Pos(1, y, 0),
              "OAK_PLANKS",
              "AIR",
              null);
      capacity.addProject(job.project, List.of(job));
      builder = new VillagerWorker(plugin, crafter, capacity);
      register(builder);
      require(capacity.claim(job.id, builder.id(), System.currentTimeMillis()), "claim");
      var begin = VillagerWorker.class.getDeclaredMethod("begin", Job.class);
      begin.setAccessible(true);
      begin.invoke(builder, job);
      builder.start();

      var abandoned = village(12);
      var cook = actor(12, abandoned);
      Furnace pending = furnace(14, 0);
      pending.getInventory().setSmelting(new ItemStack(Material.SAND, 1));
      block(14, 0, 2).setType(Material.CHEST, false);
      Chest chest = (Chest) block(14, 0, 2).getState();
      chest.getBlockInventory().addItem(new ItemStack(Material.COAL, 1));
      abandoned.chest(new Pos(14, y, 2));
      abandoned.stock(new Pos(14, y, 2), Map.of("COAL", 1), System.currentTimeMillis());
      var cooking = tools(cook, abandoned);

      var busy = village(24);
      var alternate = actor(24, busy);
      Furnace occupied = furnace(25, 0), free = furnace(27, 0);
      occupied.getInventory().setResult(new ItemStack(Material.IRON_INGOT, 1));
      alternate
          .getInventory()
          .addItem(new ItemStack(Material.SAND, 1), new ItemStack(Material.COAL, 1));
      var switching = tools(alternate, busy);

      var forest = village(36);
      var cutter = actor(36, forest);
      block(37, -1, 0).setType(Material.DIRT, false);
      for (int h = 0; h <= 4; h++) block(37, h, 0).setType(Material.OAK_LOG, false);
      block(37, 5, 0).setType(Material.OAK_LEAVES, false);
      var nav = navigation(cutter, forest);
      // Reproduce an exhausted approach search, then resume after a normal task reset.
      var oldTarget = WorkerNavigation.class.getDeclaredField("workTarget");
      oldTarget.setAccessible(true);
      oldTarget.set(nav, new Pos(37, y + 4, 0));
      var oldRejections = WorkerNavigation.class.getDeclaredField("rejectedWorkPositions");
      oldRejections.setAccessible(true);
      var rejected = (Set<Pos>) oldRejections.get(nav);
      for (int x = 32; x <= 42; x++)
        for (int z = -5; z <= 5; z++)
          for (int dy = -2; dy <= 8; dy++) rejected.add(new Pos(x, y + dy, z));
      nav.stop();
      var gathering =
          new GatheringActions(
              plugin,
              cutter,
              forest,
              nav,
              new RecoveryPolicy(System.currentTimeMillis(), 60000),
              (now, reason) -> {
                throw new AssertionError(reason);
              });
      long started = System.currentTimeMillis();
      fixture
          .getLogger()
          .info(
              "BLOCKER RECOVERY SEEDED: full crafting inventory; unfueled sand; occupied nearest"
                  + " furnace; five-log trunk");
      boolean[] verified = new boolean[4];
      Bukkit.getRegionScheduler()
          .runAtFixedRate(
              fixture,
              new Location(world, 0, y, 0),
              task -> {
                try {
                  long now = System.currentTimeMillis();
                  controls.forEach(c -> c.working(true));
                  if (!verified[0] && capacity.allComplete(job.project)) {
                    builder.stop();
                    require(block(2, 0, 0).getType() == Material.OAK_PLANKS, "paid plank missing");
                    require(
                        InventoryOps.count(crafter.getInventory(), Material.OAK_LOG) == 63,
                        "crafting log conservation");
                    require(
                        InventoryOps.count(crafter.getInventory(), Material.OAK_PLANKS) == 3,
                        "plank remainder");
                    require(capacity.caches().size() == 1, "capacity cache absent");
                    var cached =
                        (Item)
                            world.getEntity(UUID.fromString(capacity.caches().getFirst().entity()));
                    require(
                        cached != null && cached.getItemStack().getAmount() == 64,
                        "cached stack changed");
                    require(capacity.jobs().getFirst().failures == 0, "capacity failed task");
                    verified[0] = true;
                    fixture
                        .getLogger()
                        .info(
                            "BLOCKER CAPACITY PASS: log stack retained, 1 placed + 3 carried"
                                + " planks, 64-item bundle, no task failure");
                  }
                  if (!verified[1] && cooking.prepareItem("GLASS", now, pos(cook)).ready()) {
                    require(chest.getInventory().isEmpty(), "fuel not withdrawn");
                    require(pending.getInventory().getSmelting() == null, "input not consumed");
                    require(
                        InventoryOps.count(cook.getInventory(), Material.GLASS) == 1,
                        "missing cooked glass");
                    verified[1] = true;
                    fixture
                        .getLogger()
                        .info(
                            "BLOCKER FUEL PASS: abandoned batch refueled from chest and actual"
                                + " glass collected");
                  }
                  if (!verified[2] && switching.prepareItem("GLASS", now, pos(alternate)).ready()) {
                    require(
                        occupied.getInventory().getResult() != null
                            && occupied.getInventory().getResult().getType() == Material.IRON_INGOT,
                        "other recipe stolen");
                    require(
                        free.getInventory().getSmelting() == null,
                        "second furnace input not consumed");
                    require(
                        InventoryOps.count(alternate.getInventory(), Material.GLASS) == 1,
                        "alternate glass missing");
                    verified[2] = true;
                    fixture
                        .getLogger()
                        .info(
                            "BLOCKER STATION PASS: occupied nearest furnace skipped; other furnace"
                                + " cooked glass");
                  }
                  if (!verified[3]) {
                    gathering.gather("LOG", now, pos(cutter));
                    if (InventoryOps.count(cutter.getInventory(), Material.OAK_LOG) > 0) {
                      require(
                          block(37, 4, 0).getType().isAir(), "upper selected log not harvested");
                      require(
                          block(37, 0, 0).getType() == Material.OAK_LOG, "wrong trunk harvested");
                      verified[3] = true;
                      fixture
                          .getLogger()
                          .info(
                              "BLOCKER TREE PASS: upper log physically harvested after task reset,"
                                  + " approach and facing delay from ground");
                    }
                  }
                  if (verified[0] && verified[1] && verified[2] && verified[3]) {
                    task.cancel();
                    controls.forEach(c -> c.working(false));
                    fixture
                        .getLogger()
                        .info(
                            "BLOCKER RECOVERY PASS: all four executor regressions verified;"
                                + " inference disabled");
                    Bukkit.getGlobalRegionScheduler()
                        .runDelayed(fixture, t -> Bukkit.shutdown(), 20);
                  } else if (now - started > 60000)
                    throw new AssertionError(
                        "deadline "
                            + Arrays.toString(verified)
                            + " builder="
                            + builder.inspection());
                } catch (Throwable error) {
                  task.cancel();
                  fail(error);
                }
              },
              5,
              5);
    } catch (Throwable error) {
      fail(error);
    }
  }

  @SuppressWarnings("unchecked")
  private Settlement village(int x) throws Exception {
    var data = new Settlement.Data();
    data.world = world.getUID().toString();
    data.center = new Pos(x, y, 0);
    var v = new Settlement(data);
    var f = CivilizationsPlugin.class.getDeclaredField("settlements");
    f.setAccessible(true);
    ((Map<String, Settlement>) f.get(plugin)).put(v.id(), v);
    return v;
  }

  private Villager actor(int x, Settlement village) throws Exception {
    var a = world.spawn(new Location(world, x + .5, y, .5), Villager.class);
    a.setAdult();
    a.setPersistent(true);
    village.enroll(a.getUniqueId().toString(), 20);
    register(new VillagerWorker(plugin, a, village));
    controls.add(
        new WorkMovementControl(
            a,
            reason -> {
              throw new AssertionError(reason);
            }));
    return a;
  }

  @SuppressWarnings("unchecked")
  private void register(VillagerWorker worker) throws Exception {
    var f = CivilizationsPlugin.class.getDeclaredField("workers");
    f.setAccessible(true);
    ((Map<String, VillagerWorker>) f.get(plugin)).put(worker.id(), worker);
  }

  private WorkerNavigation navigation(Villager a, Settlement v) {
    return new WorkerNavigation(
        plugin,
        a,
        v,
        (now, r) -> {
          throw new AssertionError(r);
        });
  }

  private ToolActions tools(Villager a, Settlement v) {
    return new ToolActions(
        plugin,
        a,
        v,
        navigation(a, v),
        (now, r) -> {
          throw new AssertionError(r);
        });
  }

  private Furnace furnace(int x, int z) {
    block(x, 0, z).setType(Material.FURNACE, false);
    return (Furnace) block(x, 0, z).getState();
  }

  private Block block(int x, int dy, int z) {
    return world.getBlockAt(x, y + dy, z);
  }

  private Pos pos(Villager a) {
    var p = a.getLocation();
    return new Pos(p.getBlockX(), p.getBlockY(), p.getBlockZ());
  }

  private void require(boolean c, String m) {
    if (!c) throw new AssertionError(m);
  }

  private void fail(Throwable error) {
    fixture.getLogger().severe("BLOCKER RECOVERY FAIL: " + error);
    error.printStackTrace();
    if (builder != null) builder.stop();
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> Bukkit.shutdown(), 20);
  }
}
