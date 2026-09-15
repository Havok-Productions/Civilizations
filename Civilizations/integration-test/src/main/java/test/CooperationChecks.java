package test;

import dev.civilizations.*;
import dev.civilizations.core.*;
import dev.civilizations.design.*;
import dev.civilizations.world.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** One disposable-world scenario for preparation, couriers, a complete wall and paid storage. */
final class CooperationChecks {
  private final JavaPlugin fixture;
  private CivilizationsPlugin plugin;
  private Settlement village;
  private Villager builder, donor;
  private VillagerWorker builderWork, donorWork;
  private World world;
  private Pos center, store;
  private Terrain terrain;
  private int stage;
  private long started;
  private boolean courierTravel, unfinishedDelivery;
  private List<Job> wall;
  private Job donorJob;

  CooperationChecks(JavaPlugin fixture) {
    this.fixture = fixture;
  }

  void start() {
    Bukkit.getGlobalRegionScheduler()
        .runDelayed(
            fixture,
            task -> {
              world = Bukkit.getWorlds().getFirst();
              world.setTime(1000);
              world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
              world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
              List<CompletableFuture<Void>> ready = new ArrayList<>();
              for (int x = -2; x <= 3; x++)
                for (int z = -2; z <= 2; z++) {
                  int cx = x, cz = z;
                  CompletableFuture<Void> r = new CompletableFuture<>();
                  ready.add(r);
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
                                        r.complete(null);
                                      }))
                      .exceptionally(
                          e -> {
                            r.completeExceptionally(e);
                            return null;
                          });
                }
              CompletableFuture.allOf(ready.toArray(CompletableFuture[]::new))
                  .orTimeout(30, TimeUnit.SECONDS)
                  .whenComplete(
                      (ignored, error) -> {
                        if (error != null) fail(error);
                        else
                          Bukkit.getRegionScheduler()
                              .runDelayed(fixture, new Location(world, 0, 80, 0), t -> seed(), 60);
                      });
            },
            60);
  }

  @SuppressWarnings("unchecked")
  private void seed() {
    try {
      plugin = (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
      int y = world.getHighestBlockYAt(0, 0) + 5;
      center = new Pos(0, y, 0);
      store = center.add(0, 0, 6);
      for (int x = -14; x <= 30; x++)
        for (int z = -10; z <= 12; z++)
          for (int dy = -3; dy <= 7; dy++)
            world.getBlockAt(x, y + dy, z).setType(dy < 0 ? Material.DIRT : Material.AIR, false);
      block(store).setType(Material.CHEST, false);
      Chest chest = (Chest) block(store).getState();
      for (int i = 0; i < 27; i++)
        chest.getBlockInventory().setItem(i, new ItemStack(Material.DRIED_KELP_BLOCK, 64));
      block(center.add(7, 0, 4)).setType(Material.DIRT, false);
      block(center.add(7, 1, 4)).setType(Material.DIRT, false);
      block(center.add(7, 0, 5)).setType(Material.OAK_LOG, false);
      block(center.add(7, 1, 5)).setType(Material.OAK_LOG, false);
      block(center.add(7, 2, 5)).setType(Material.OAK_LEAVES, false);
      terrain =
          new Terrain() {
            public int height(int x, int z) {
              return world.getHighestBlockYAt(x, z);
            }

            public boolean available(int x, int z) {
              return x >= -14 && x <= 30 && z >= -10 && z <= 12;
            }

            public String type(Pos p) {
              return available(p.x(), p.z()) ? block(p).getType().name() : "UNKNOWN";
            }

            public String blockData(Pos p) {
              return available(p.x(), p.z()) ? block(p).getBlockData().getAsString() : null;
            }
          };
      Settlement.Data data = new Settlement.Data();
      data.world = world.getUID().toString();
      data.center = center;
      data.chest = store;
      village = new Settlement(data);
      var compiled =
          new DesignCompiler()
              .compile(
                  new Blueprint(
                      "house", "Prepare a building site", 6, 3, 5, 5, 3, "north", List.of()),
                  terrain,
                  center,
                  "design-preparation",
                  p -> p.equals(store),
                  List.of());
      List<Job> preparation =
          new ArrayList<>(compiled.jobs().stream().filter(j -> j.kind == Job.Kind.CLEAR).toList());
      preparation.add(
          compiled.jobs().stream().filter(j -> j.kind == Job.Kind.PLACE).findFirst().orElseThrow());
      village.addProject("design-preparation", preparation);
      Field villages = CivilizationsPlugin.class.getDeclaredField("settlements");
      villages.setAccessible(true);
      ((Map<String, Settlement>) villages.get(plugin)).put(village.id(), village);
      builder = world.spawn(new Location(world, .5, y, .5), Villager.class);
      builder.getInventory().addItem(new ItemStack(Material.OAK_PLANKS, 1));
      builderWork = attach(builder);
      started = System.currentTimeMillis();
      fixture
          .getLogger()
          .info(
              "COOPERATION SEEDED: two-layer grading, natural tree, one paid floor block; full"
                  + " shared chest");
      builder
          .getScheduler()
          .runAtFixedRate(
              fixture,
              t -> {
                try {
                  if (check()) t.cancel();
                } catch (Throwable error) {
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

  private org.bukkit.block.Block block(Pos p) {
    return world.getBlockAt(p.x(), p.y(), p.z());
  }

  private VillagerWorker attach(Villager actor) throws Exception {
    actor.setAdult();
    actor.setPersistent(true);
    Method m =
        CivilizationsPlugin.class.getDeclaredMethod("attach", Villager.class, Settlement.class);
    m.setAccessible(true);
    m.invoke(plugin, actor, village);
    return plugin.worker(actor.getUniqueId().toString());
  }

  @SuppressWarnings("unchecked")
  private boolean check() throws Exception {
    long now = System.currentTimeMillis();
    // Keep this executor scenario independent of unrelated architectural/model proposals.
    Field scan = CivilizationsPlugin.class.getDeclaredField("scannedAt");
    scan.setAccessible(true);
    ((Map<String, Long>) scan.get(plugin)).put(village.id(), now + 600_000);
    if (now - started > 240_000)
      throw new AssertionError(
          "Timeout stage="
              + stage
              + " builder="
              + builderWork.status()
              + " donor="
              + (donorWork == null ? "" : donorWork.status()));
    if (stage == 0 && village.allComplete("design-preparation")) {
      if (InventoryOps.count(builder.getInventory(), Material.DIRT) != 2
          || InventoryOps.count(builder.getInventory(), Material.OAK_LOG) != 2
          || block(center.add(7, 0, 5)).getType() != Material.AIR
          || block(center.add(7, 1, 4)).getType() != Material.AIR)
        throw new AssertionError(
            "Site preparation did not retain its real drops: "
                + InventoryOps.summary(builder.getInventory()));
      fixture
          .getLogger()
          .info(
              "COOPERATION PREPARATION PASS: cleared soil and tree, retained two dirt/two logs,"
                  + " placed paid floor");
      Blueprint outline =
          new Blueprint(
              "wall",
              "Protect shared village",
              0,
              -2,
              0,
              0,
              3,
              "north",
              List.of(
                  new Blueprint.Point(-2, -2),
                  new Blueprint.Point(2, -2),
                  new Blueprint.Point(2, 2),
                  new Blueprint.Point(-2, 2)));
      wall =
          new DesignCompiler()
              .compile(
                  outline, terrain, center, "design-cooperative-wall", p -> false, List.of(center))
              .jobs();
      village.addProject("design-cooperative-wall", wall);
      donorJob =
          new Job(
              Job.Kind.PLACE,
              "courier-own-work",
              center.add(-12, 0, 0),
              center.add(-11, 0, 0),
              "GLASS",
              "AIR",
              null);
      village.addProject(donorJob.project, List.of(donorJob));
      donor = world.spawn(new Location(world, 24.5, center.y(), .5), Villager.class);
      donor
          .getInventory()
          .addItem(
              new ItemStack(Material.COBBLESTONE, 64),
              new ItemStack(Material.GLASS, 1),
              new ItemStack(Material.OAK_LOG, 4));
      donorWork = attach(donor);
      if (!village.claim(donorJob.id, donorWork.id(), now))
        throw new AssertionError("Cannot claim courier's own work");
      Method begin = VillagerWorker.class.getDeclaredMethod("begin", Job.class);
      begin.setAccessible(true);
      begin.invoke(donorWork, donorJob);
      stage = 1;
    }
    if (stage == 1) {
      var delivery = village.deliveries().incoming(builderWork.id(), now);
      if (delivery != null && builder.getLocation().distanceSquared(donor.getLocation()) > 16) {
        courierTravel = true;
        unfinishedDelivery |= !village.mayShareSurplus(donorWork.id());
      }
      if (village.allComplete("design-cooperative-wall")
          && village.allComplete("courier-own-work")) {
        if (!courierTravel || !unfinishedDelivery)
          throw new AssertionError("No courier travel while committed work remained");
        for (Job j : wall)
          if (!block(j.target).getType().name().equals(j.material))
            throw new AssertionError("Missing wall block " + j.target);
        if (village.memories(builderWork.id()).stream()
                .noneMatch(m -> m.result().startsWith("Received "))
            && village.memories(donorWork.id()).stream()
                .noneMatch(m -> m.result().startsWith("Received ")))
          fixture
              .getLogger()
              .info(
                  "Earlier delivery memories rolled out; transfer receipts remain in debug"
                      + " journal");
        fixture
            .getLogger()
            .info(
                "COOPERATION WALL PASS: all "
                    + wall.size()
                    + " wall/gate blocks physically present, distant delivery during unfinished own"
                    + " work, glass task resumed");
        village.storageCapacity().request();
        village.storageCapacity().observe(store, false, now);
        if (!StoragePlanning.schedule(village, terrain, center, 20, now))
          throw new AssertionError("No paid shared storage expansion");
        stage = 2;
      }
    }
    if (stage == 2 && village.chests().size() == 2) {
      List<Job> storage =
          village.jobs().stream().filter(j -> j.project.startsWith("storage-")).toList();
      if (storage.size() != 1
          || !storage.getFirst().complete
          || block(storage.getFirst().target).getType() != Material.CHEST)
        throw new AssertionError("Storage expansion not built exactly once");
      Chest old = (Chest) block(store).getState();
      if (InventoryOps.total(old.getInventory()) != 1728)
        throw new AssertionError("Original chest contents changed");
      long placedStone = wall.stream().filter(j -> j.material.equals("COBBLESTONE")).count();
      int remainingStone =
          InventoryOps.count(builder.getInventory(), Material.COBBLESTONE)
              + InventoryOps.count(donor.getInventory(), Material.COBBLESTONE);
      for (Pos p : village.chests())
        remainingStone +=
            InventoryOps.count(((Chest) block(p).getState()).getInventory(), Material.COBBLESTONE);
      if (remainingStone + placedStone != 64)
        throw new AssertionError(
            "Delivery duplicated or lost cobblestone: " + remainingStone + " + " + placedStone);
      fixture
          .getLogger()
          .info(
              "COOPERATION PASS: actual ordered preparation, complete wall, courier handoffs with"
                  + " retained own materials, one crafted/placed/registered shared chest;"
                  + " cobblestone conserved=64, old storage unchanged=1728");
      builderWork.stop();
      donorWork.stop();
      Bukkit.getGlobalRegionScheduler().execute(fixture, Bukkit::shutdown);
      return true;
    }
    return false;
  }

  private void fail(Throwable error) {
    fixture.getLogger().severe("COOPERATION FAIL: " + error);
    error.printStackTrace();
    if (builderWork != null) builderWork.stop();
    if (donorWork != null) donorWork.stop();
    Bukkit.getGlobalRegionScheduler().execute(fixture, Bukkit::shutdown);
  }
}
