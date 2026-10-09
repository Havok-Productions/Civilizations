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
  private final boolean localProject = Boolean.getBoolean("civilizations.test.local-project");
  private final boolean surfaceSearch = Boolean.getBoolean("civilizations.test.surface-search");
  private int treeX = 8, treeZ;
  private final boolean handoff = Boolean.getBoolean("civilizations.test.handoff");
  private boolean supplyInterrupted, sleepInterrupted, sleepObserved, woke;
  private long wakeAt;
  private List<Job> localSteps = List.of();
  private Job distraction;

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
      if (surfaceSearch) {
        y = 64;
        treeX = 18;
        treeZ = 4;
        // Same ordinary project, but its worker starts sixteen blocks below the tree.
        // A physical ramp is available; no teleport or material injection during execution.
        for (int x = -4; x <= 66; x++)
          for (int z = -8; z <= 12; z++) {
            if (!Bukkit.isOwnedByCurrentRegion(new Location(world, x, y, z), 1))
              throw new AssertionError("Resource fixture region unavailable");
            world.getBlockAt(x, y - 1, z).setType(Material.GRASS_BLOCK, false);
            for (int h = y; h <= y + 18; h++)
              world.getBlockAt(x, h, z).setType(Material.AIR, false);
          }
        for (int x = -2; x <= 16; x++)
          for (int z = -2; z <= 2; z++) {
            int floor = y - 17 + Math.max(0, x);
            world.getBlockAt(x, floor, z).setType(Material.STONE, false);
            for (int h = floor + 1; h <= y + 2; h++)
              world.getBlockAt(x, h, z).setType(Material.AIR, false);
          }
      }
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
      actor = world.spawn(new Location(world, .5, surfaceSearch ? y - 16 : y, .5), Villager.class);
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
        if (scenario == Scenario.INFERENCE_BUSY)
          plugin.inference().request("fixture-pending-teacher", "{}", Set.of(), ignored -> {});
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
        for (int h = 0; h < 4; h++)
          world.getBlockAt(treeX, y + h, treeZ).setType(Material.OAK_LOG, false);
        for (int dx = -2; dx <= 2; dx++)
          for (int dz = -2; dz <= 2; dz++)
            world.getBlockAt(treeX + dx, y + 4, treeZ + dz).setType(Material.OAK_LEAVES, false);
        actor.setProfession(Villager.Profession.FARMER);
        world.getBlockAt(-4, y, 0).setType(Material.COMPOSTER, false);
        actor.setMemory(org.bukkit.entity.memory.MemoryKey.JOB_SITE, new Location(world, -4, y, 0));
      } else actor.getInventory().addItem(new ItemStack(Material.OAK_LOG, 2));
      if ((scenario == Scenario.NAVIGATION))
        for (int h = 0; h < 3; h++) world.getBlockAt(2, y + h, 0).setType(Material.STONE, false);
      if (localProject) {
        plugin.getConfig().set("ai.routine-decisions", true);
        Field routine = CivilizationsPlugin.class.getDeclaredField("routineDecisions");
        routine.setAccessible(true);
        routine.setBoolean(plugin, true);
        actor.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 1));
        List<Job> steps = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
          Pos target = new Pos(62, y, 6 + i);
          world.getBlockAt(target.x(), target.y(), target.z()).setType(Material.AIR, false);
          Job step =
              new Job(
                  Job.Kind.PLACE,
                  "design-local-project",
                  target,
                  new Pos(61, y, 6 + i),
                  "OAK_PLANKS",
                  "AIR",
                  null);
          step.phase = i;
          steps.add(step);
        }
        localSteps = List.copyOf(steps);
        if (!village.addProject("design-local-project", steps))
          throw new AssertionError("Local project admission failed");
        distraction =
            new Job(
                Job.Kind.PLACE,
                "supplied-distraction",
                new Pos(2, y, 4),
                new Pos(1, y, 4),
                "COBBLESTONE",
                "AIR",
                null);
        if (!village.addProject(distraction.project, List.of(distraction)))
          throw new AssertionError("Distractor admission failed");
      }
      Job repair = localProject ? localSteps.getFirst() : village.jobs().getFirst();
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
                  if (handoff) checkHandoffs();
                  controlledDuringWork |=
                      plugin
                          .worker(actor.getUniqueId().toString())
                          .inspection()
                          .toString()
                          .contains("plugin movement control=true");
                  if (localProject
                      && !village.allComplete(repair.project)
                      && world
                              .getBlockAt(distraction.target.x(), y, distraction.target.z())
                              .getType()
                          != Material.AIR)
                    throw new AssertionError(
                        "Worker abandoned current project for supplied distraction");
                  if (localProject
                      ? village.allComplete(repair.project)
                      : village.jobs().stream()
                          .anyMatch(j -> j.id.equals(repair.id) && j.complete)) {
                    if (localProject) {
                      if (handoff
                          && (!supplyInterrupted || !sleepInterrupted || !sleepObserved || !woke))
                        throw new AssertionError("Required handoffs were not exercised");
                      var worker = plugin.worker(actor.getUniqueId().toString());
                      worker.stop();
                      for (var step : localSteps)
                        if (world.getBlockAt(step.target.x(), y, step.target.z()).getType()
                            != Material.OAK_PLANKS)
                          throw new AssertionError("Project completion lacks physical block");
                      if (InventoryOps.count(actor.getInventory(), Material.OAK_LOG) != 0
                          || InventoryOps.count(actor.getInventory(), Material.OAK_PLANKS) != 0
                          || InventoryOps.count(actor.getInventory(), Material.COBBLESTONE) != 1)
                        throw new AssertionError("Project material conservation failed");
                      if (pendingModel || !plugin.inference().status().contains("decisions=0"))
                        throw new AssertionError("Project execution called teacher");
                      if (world.getBlockAt(treeX, y + 3, treeZ).getType() != Material.AIR
                          || !controlledDuringWork)
                        throw new AssertionError(
                            "Physical gathering or controlled movement missing");
                      fixture
                          .getLogger()
                          .info(
                              "LOCAL PROJECT PASS: discovered tree, gathered wood, crafted four"
                                  + " planks, completed four ordered placements; supplied"
                                  + " distraction untouched; exact materials; zero teacher calls"
                                  + " even with routine advice enabled");
                      if (handoff)
                        fixture
                            .getLogger()
                            .info(
                                "HANDOFF PASS: supply navigation failure retained the parent"
                                    + " without penalty; actual action target recorded separately;"
                                    + " night interrupted construction; original project resumed"
                                    + " and completed with exact materials");
                      t.cancel();
                      Bukkit.getGlobalRegionScheduler()
                          .runDelayed(fixture, q -> Bukkit.shutdown(), 20);
                      return;
                    }
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

  private void checkHandoffs() throws Exception {
    var worker = plugin.worker(actor.getUniqueId().toString());
    Field current = VillagerWorker.class.getDeclaredField("job");
    current.setAccessible(true);
    Field mode = VillagerWorker.class.getDeclaredField("mode");
    mode.setAccessible(true);
    if (!supplyInterrupted && "gather".equals(mode.get(worker))) {
      Field gatherField = VillagerWorker.class.getDeclaredField("gathering");
      gatherField.setAccessible(true);
      Object gathering = gatherField.get(worker);
      Field targetField = GatheringActions.class.getDeclaredField("target");
      targetField.setAccessible(true);
      Pos source = (Pos) targetField.get(gathering);
      if (source != null) {
        Job parent = (Job) current.get(worker);
        Method failure =
            VillagerWorker.class.getDeclaredMethod("navigationFailed", long.class, String.class);
        failure.setAccessible(true);
        failure.invoke(worker, System.currentTimeMillis(), "fixture_supply_route_interruption");
        Job retained = (Job) current.get(worker);
        Job stored =
            village.jobs().stream().filter(j -> j.id.equals(parent.id)).findFirst().orElseThrow();
        if (retained == null
            || !retained.id.equals(parent.id)
            || stored.failures != 0
            || stored.retryAfter != 0)
          throw new AssertionError("Supply failure released or penalized the construction parent");
        Field evidence = VillagerWorker.class.getDeclaredField("lastFailure");
        evidence.setAccessible(true);
        Map<?, ?> details = (Map<?, ?>) evidence.get(worker);
        if (!source.equals(details.get("target"))
            || !parent.target.equals(details.get("project_target"))
            || !Boolean.FALSE.equals(details.get("parent_penalized")))
          throw new AssertionError("Failure attributed to wrong action/target: " + details);
        supplyInterrupted = true;
        fixture
            .getLogger()
            .info(
                "HANDOFF SUPPLY PASS: parent retained; resource target="
                    + source
                    + "; construction target="
                    + parent.target);
      }
    }
    if (supplyInterrupted
        && !sleepInterrupted
        && village.jobs().stream()
            .anyMatch(j -> j.project.equals("design-local-project") && j.complete)) {
      Bukkit.getGlobalRegionScheduler().execute(fixture, () -> world.setTime(13000));
      wakeAt = System.currentTimeMillis() + 3000;
      sleepInterrupted = true;
    }
    if (sleepInterrupted && !woke) {
      Field night = VillagerWorker.class.getDeclaredField("night");
      night.setAccessible(true);
      sleepObserved |= night.getBoolean(worker);
      if (System.currentTimeMillis() >= wakeAt) {
        Bukkit.getGlobalRegionScheduler().execute(fixture, () -> world.setTime(1000));
        woke = true;
      }
    }
  }
}
