package test;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import dev.civilizations.world.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Targeted movement -> stored structure damage -> wood crafting -> real repair. */
final class StallChecks {
  private final JavaPlugin fixture;
  private CivilizationsPlugin plugin;
  private World world;
  private Settlement village;
  private Villager actor;
  private Pos wall;
  private int y;
  private volatile boolean pendingModel;
  private boolean controlledDuringWork;
  private final Scenario scenario;

  StallChecks(JavaPlugin fixture, Scenario scenario) {
    this.fixture = fixture;
    this.scenario = scenario;
  }

  void start() {
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> prepare(), 60);
  }

  void prepare() {
    plugin = (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
    world = Bukkit.getWorlds().getFirst();
    world.setTime(1000);
    List<CompletableFuture<Void>> waits = new ArrayList<>();
    for (int x = -3; x <= 7; x++)
      for (int z = -3; z <= 3; z++) {
        int cx = x, cz = z;
        CompletableFuture<Void> f = new CompletableFuture<>();
        waits.add(f);
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
                              f.complete(null);
                            }));
      }
    CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new))
        .thenRun(
            () ->
                Bukkit.getRegionScheduler()
                    .runDelayed(fixture, new Location(world, 0, 1, 0), t -> seed(), 60));
  }

  @SuppressWarnings("unchecked")
  void seed() {
    try {
      y = world.getHighestBlockYAt(0, 0) + 1;
      wall = new Pos(62, y, 0);
      if (!Bukkit.isOwnedByCurrentRegion(new Location(world, 62, y, 0), 1))
        throw new AssertionError("Fixture region unavailable");
      Settlement.Data d = new Settlement.Data();
      d.world = world.getUID().toString();
      d.center = new Pos(0, y, 0);
      d.radius = 12;
      d.beds.add(new Pos(60, y, 2));
      village = new Settlement(d);
      Field f = CivilizationsPlugin.class.getDeclaredField("settlements");
      f.setAccessible(true);
      ((Map<String, Settlement>) f.get(plugin)).put(village.id(), village);
      world.getBlockAt(62, y, 0).setType(Material.OAK_PLANKS, false);
      Terrain observed =
          new Terrain() {
            public boolean available(int x, int z) {
              return Bukkit.isOwnedByCurrentRegion(new Location(world, x, y, z), 1);
            }

            public int height(int x, int z) {
              return available(x, z) ? world.getHighestBlockYAt(x, z) : -64;
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
      if (VillageRepairs.survey(village, observed).get("newly_recorded") < 1)
        throw new AssertionError("Existing wall not recorded");
      world.getBlockAt(62, y, 0).setType(Material.AIR, false);
      if (VillageRepairs.survey(village, observed).get("repair_jobs_added") != 1)
        throw new AssertionError("Observed damage did not create repair");
      actor = world.spawn(new Location(world, .5, y, .5), Villager.class);
      actor.setAdult();
      if ((scenario == Scenario.NAVIGATION)) {
        // The U opens away from the goal. A straight-line local waypoint cannot leave it.
        for (int z = -3; z <= 3; z++)
          for (int h = 0; h < 3; h++) world.getBlockAt(2, y + h, z).setType(Material.STONE, false);
        for (int x = -2; x <= 2; x++)
          for (int h = 0; h < 3; h++) {
            world.getBlockAt(x, y + h, -3).setType(Material.STONE, false);
            world.getBlockAt(x, y + h, 3).setType(Material.STONE, false);
          }
        // Broad natural barrier beyond the first map, requiring a fresh map and bounded cut.
        for (int z = -32; z <= 32; z++)
          for (int h = 0; h < 3; h++) world.getBlockAt(30, y + h, z).setType(Material.DIRT, false);
      }
      if ((scenario == Scenario.INFERENCE_BUSY) || (scenario == Scenario.AUTONOMY)) {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        var backend =
            new dev.civilizations.ai.ModelBackend() {
              public boolean ready() {
                return true;
              }

              public String status() {
                return "deliberately stalled test backend";
              }

              public String complete(String system, String user) throws Exception {
                pendingModel = true;
                if (!(scenario == Scenario.AUTONOMY)) release.await();
                return "{\"action\":\"rest\"}";
              }

              public void close() {
                release.countDown();
              }
            };
        plugin.inference().close();
        Field queue = CivilizationsPlugin.class.getDeclaredField("inference");
        queue.setAccessible(true);
        queue.set(plugin, new dev.civilizations.ai.InferenceQueue(backend, 4));
      }
      village.enroll(actor.getUniqueId().toString(), 20);
      // Verify a real one-block soil cut and retained dirt, with ordinary protection checks.
      Pos spoil = new Pos(2, y, 0);
      world.getBlockAt(2, y, 0).setType(Material.DIRT, false);
      if ((scenario == Scenario.NAVIGATION)) {
        world.getBlockAt(2, y + 1, 0).setType(Material.AIR, false);
        world.getBlockAt(2, y + 2, 0).setType(Material.AIR, false);
      }
      Job clear =
          new Job(Job.Kind.CLEAR, "design-clear", spoil, new Pos(1, y, 0), "", "DIRT", null);
      if (!plugin.mayChange(actor, world.getBlockAt(2, y, 0), "CLEAR"))
        throw new AssertionError("Unprotected clearance refused");
      String issue = ClearingActions.work(plugin, actor, clear, world.getBlockAt(2, y, 0));
      if (issue != null
          || !world.getBlockAt(2, y, 0).getType().isAir()
          || InventoryOps.count(actor.getInventory(), Material.DIRT) != 1)
        throw new AssertionError("Clearance failed: " + issue);
      Pos protectedSoil = new Pos(3, y, 0);
      world.getBlockAt(3, y, 0).setType(Material.DIRT, false);
      village.playerPlaced(protectedSoil);
      if (plugin.mayChange(actor, world.getBlockAt(3, y, 0), "CLEAR"))
        throw new AssertionError("Player protection bypassed");
      // Keep that fixture obstruction out of the movement corridor.
      world.getBlockAt(3, y, 0).setType(Material.AIR, false);
      actor.getInventory().clear();
      if ((scenario == Scenario.AUTONOMY)) {
        for (int h = 0; h < 4; h++) world.getBlockAt(8, y + h, 0).setType(Material.OAK_LOG, false);
        for (int dx = -2; dx <= 2; dx++)
          for (int dz = -2; dz <= 2; dz++)
            world.getBlockAt(8 + dx, y + 4, dz).setType(Material.OAK_LEAVES, false);
        actor.setProfession(Villager.Profession.FARMER);
        world.getBlockAt(-4, y, 0).setType(Material.COMPOSTER, false);
        actor.setMemory(org.bukkit.entity.memory.MemoryKey.JOB_SITE, new Location(world, -4, y, 0));
      } else actor.getInventory().addItem(new ItemStack(Material.OAK_LOG, 2));
      if ((scenario == Scenario.NAVIGATION))
        for (int h = 0; h < 3; h++) world.getBlockAt(2, y + h, 0).setType(Material.STONE, false);
      Job repair = village.jobs().getFirst();
      village.taskProject(actor.getUniqueId().toString(), repair.project);
      Method attach =
          CivilizationsPlugin.class.getDeclaredMethod("attach", Villager.class, Settlement.class);
      attach.setAccessible(true);
      attach.invoke(plugin, actor, village);
      fixture
          .getLogger()
          .info(
              "CLEARANCE PASS: unprotected soil removed, dirt retained; player-protected soil"
                  + " rejected");
      long start = System.currentTimeMillis();
      actor
          .getScheduler()
          .runAtFixedRate(
              fixture,
              t -> {
                try {
                  controlledDuringWork |=
                      plugin
                          .worker(actor.getUniqueId().toString())
                          .inspection()
                          .toString()
                          .contains("plugin movement control=true");
                  if (village.jobs().stream().anyMatch(j -> j.id.equals(repair.id) && j.complete)) {
                    if ((scenario == Scenario.INFERENCE_BUSY) && !pendingModel)
                      throw new AssertionError(
                          "No deliberately pending model request was exercised");
                    if (!Bukkit.isOwnedByCurrentRegion(new Location(world, 62, y, 0), 1)
                        || world.getBlockAt(62, y, 0).getType() != Material.OAK_PLANKS
                        || InventoryOps.count(actor.getInventory(), Material.OAK_LOG)
                            != ((scenario == Scenario.AUTONOMY) ? 0 : 1))
                      throw new AssertionError(
                          "Repair did not consume real wood/place recorded block");
                    if ((scenario == Scenario.AUTONOMY)) {
                      if (world.getBlockAt(8, y + 3, 0).getType() != Material.AIR)
                        throw new AssertionError("Nearby tree not harvested");
                      if (!controlledDuringWork)
                        throw new AssertionError("Work movement adapter not active");
                      if (pendingModel || !plugin.inference().status().contains("decisions=0"))
                        throw new AssertionError("Routine inference was used");
                      fixture.getLogger().info("COREAI RUNTIME: " + plugin.coreAi().status());
                      fixture
                          .getLogger()
                          .info(
                              "AUTONOMY PASS: empty inventory -> independently found nearby tree"
                                  + " without village scan -> gathered log -> crafted planks ->"
                                  + " walked 58 blocks -> repaired wall; competing farm POI"
                                  + " suppressed; zero inference decisions");
                      plugin.worker(actor.getUniqueId().toString()).stop();
                      if (!plugin
                          .worker(actor.getUniqueId().toString())
                          .inspection()
                          .toString()
                          .contains("plugin movement control=false"))
                        throw new AssertionError("Native behavior not restored");
                    }
                    fixture
                        .getLogger()
                        .info(
                            "STALL RECOVERY PASS: villager walked from x=0 to x="
                                + actor.getLocation().getBlockX()
                                + "; restored recorded plank at x=62; wood consumed by repair;"
                                + " planks remaining="
                                + InventoryOps.count(actor.getInventory(), Material.OAK_PLANKS)
                                + "; AI="
                                + plugin.inference().status());
                    if ((scenario == Scenario.NAVIGATION)) {
                      if (InventoryOps.count(actor.getInventory(), Material.DIRT) < 2)
                        throw new AssertionError("Natural barrier not cleared with retained drops");
                      if (world.getBlockAt(2, y, 0).getType() != Material.STONE)
                        throw new AssertionError("Solid U-wall was modified");
                      fixture
                          .getLogger()
                          .info(
                              "TERRAIN NAVIGATION PASS: U-shaped solid wall preserved; villager"
                                  + " detoured, crossed multiple local maps, cleared natural"
                                  + " barrier, retained dirt, completed actual repair");
                    }
                    t.cancel();
                    Bukkit.getGlobalRegionScheduler()
                        .runDelayed(fixture, q -> Bukkit.shutdown(), 20);
                  } else if (System.currentTimeMillis() - start
                      > ((scenario == Scenario.NAVIGATION) ? 180_000 : 100_000)) {
                    t.cancel();
                    throw new AssertionError(
                        "Repair deadline; "
                            + plugin.worker(actor.getUniqueId().toString()).inspection());
                  }
                } catch (Throwable e) {
                  t.cancel();
                  fail(e);
                }
              },
              () -> {},
              20,
              20);
    } catch (Throwable e) {
      fail(e);
    }
  }

  void fail(Throwable e) {
    fixture.getLogger().severe("STALL RECOVERY FAIL: " + e);
    e.printStackTrace();
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> Bukkit.shutdown(), 20);
  }
}
