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

/** One combined physical case: queued maps, landing, water exit, emergency and resumed building. */
final class NavigationRecoveryChecks {
  private final JavaPlugin fixture;
  private World world;
  private CivilizationsPlugin plugin;
  private Settlement village;
  private final List<Villager> actors = new ArrayList<>();
  private final List<VillagerWorker> workers = new ArrayList<>();
  private final List<Job> jobs = new ArrayList<>();
  private final Set<Integer> finished = new HashSet<>();
  private final List<Location> previous = new ArrayList<>();
  private final double[] travel = new double[2];
  private int y;
  private boolean trapped;
  private Pos trap;
  private CompletableFuture<Void> burst;

  NavigationRecoveryChecks(JavaPlugin fixture) {
    this.fixture = fixture;
  }

  void start() {
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> prepare(), 60);
  }

  private void prepare() {
    world = Bukkit.getWorlds().getFirst();
    world.setTime(1000);
    List<CompletableFuture<Void>> loaded = new ArrayList<>();
    for (int x = -2; x <= 3; x++)
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

  @SuppressWarnings("unchecked")
  private void seed() {
    try {
      plugin = (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
      y = world.getHighestBlockYAt(0, 0) + 4;
      if (!Bukkit.isOwnedByCurrentRegion(new Location(world, 22, y, 8), 2))
        throw new AssertionError("Fixture region unavailable");
      for (int lane = 0; lane < 2; lane++) {
        int z = lane * 6;
        for (int x = -2; x <= 22; x++)
          for (int dz = -1; dz <= 1; dz++)
            for (int dy = -4; dy <= 7; dy++)
              world
                  .getBlockAt(x, y + dy, z + dz)
                  .setType(
                      dy < 0 || dz != 0 || x == -2 || x == 22 ? Material.STONE : Material.AIR,
                      false);
        for (int x = 2; x <= 6; x++)
          for (int dy = lane == 0 ? -1 : -3; dy <= -1; dy++)
            world.getBlockAt(x, y + dy, z).setType(Material.WATER, false);
      }
      var data = new Settlement.Data();
      data.world = world.getUID().toString();
      data.center = new Pos(10, y, 0);
      village = new Settlement(data);
      var villagesField = CivilizationsPlugin.class.getDeclaredField("settlements");
      villagesField.setAccessible(true);
      ((Map<String, Settlement>) villagesField.get(plugin)).put(village.id(), village);
      var workersField = CivilizationsPlugin.class.getDeclaredField("workers");
      workersField.setAccessible(true);
      var registry = (Map<String, VillagerWorker>) workersField.get(plugin);
      var begin = VillagerWorker.class.getDeclaredMethod("begin", Job.class);
      begin.setAccessible(true);
      for (int i = 0; i < 2; i++) {
        int z = i * 6;
        var actor =
            world.spawn(new Location(world, 4.5, i == 0 ? y + 4 : y - 3, z + .5), Villager.class);
        actor.setAdult();
        actor.setPersistent(true);
        actor.getInventory().addItem(new ItemStack(Material.OAK_PLANKS, 2));
        if (i == 1) actor.setRemainingAir(20);
        actors.add(actor);
        previous.add(actor.getLocation().clone());
        village.enroll(actor.getUniqueId().toString(), 20);
        Job job =
            new Job(
                Job.Kind.PLACE,
                "navigation-recovery-" + i,
                new Pos(18, y, z),
                new Pos(17, y, z),
                "OAK_PLANKS",
                "AIR",
                null);
        jobs.add(job);
        village.addProject(job.project, List.of(job));
        var worker = new VillagerWorker(plugin, actor, village);
        workers.add(worker);
        registry.put(worker.id(), worker);
        if (!village.claim(job.id, worker.id(), System.currentTimeMillis()))
          throw new AssertionError("Cannot claim test work");
        begin.invoke(worker, job);
      }
      List<CompletableFuture<NavigationService.Plan>> requests = new ArrayList<>();
      for (int i = 0; i < 12; i++)
        requests.add(
            plugin
                .navigation()
                .request(
                    world,
                    village,
                    "fixture-queue-" + i,
                    new Pos(9, y, 0),
                    new Pos(15, y, 0),
                    0,
                    "fixture"));
      burst = CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new));
      workers.forEach(VillagerWorker::start);
      long started = System.currentTimeMillis();
      Bukkit.getRegionScheduler()
          .runAtFixedRate(
              fixture,
              new Location(world, 10, y, 0),
              task -> {
                try {
                  long now = System.currentTimeMillis();
                  for (int i = 0; i < 2; i++) {
                    Villager actor = actors.get(i);
                    if (actor.isDead() || !actor.isValid())
                      throw new AssertionError("Worker died " + i);
                    Location at = actor.getLocation();
                    double delta = at.distance(previous.get(i));
                    if (delta > 2) throw new AssertionError("Unexpected position jump " + delta);
                    travel[i] += delta;
                    previous.set(i, at.clone());
                    if (i == 0
                        && !trapped
                        && at.getX() >= 10
                        && actor.isOnGround()
                        && !jobs.get(i).complete) {
                      trap = new Pos(at.getBlockX(), at.getBlockY() + 1, at.getBlockZ());
                      world.getBlockAt(trap.x(), trap.y(), trap.z()).setType(Material.DIRT, false);
                      trapped = true;
                      fixture
                          .getLogger()
                          .info(
                              "NAVIGATION RECOVERY: dirt obstruction introduced around working"
                                  + " villager; original job="
                                  + jobs.get(i).id);
                    }
                    if (jobs.get(i).complete && finished.add(i)) workers.get(i).stop();
                  }
                  if (finished.size() == 2 && burst.isDone()) {
                    burst.join();
                    if (!trapped
                        || world.getBlockAt(trap.x(), trap.y(), trap.z()).getType() != Material.AIR)
                      throw new AssertionError(
                          "Suffocation obstruction was not physically cleared");
                    for (int i = 0; i < 2; i++) {
                      var job = jobs.get(i);
                      if (world.getBlockAt(job.target.x(), job.target.y(), job.target.z()).getType()
                              != Material.OAK_PLANKS
                          || InventoryOps.count(actors.get(i).getInventory(), Material.OAK_PLANKS)
                              != 1
                          || job.failures != 0
                          || travel[i] < 10)
                        throw new AssertionError(
                            "Work/material/travel mismatch "
                                + i
                                + " "
                                + workers.get(i).inspection());
                    }
                    if (InventoryOps.count(actors.get(0).getInventory(), Material.DIRT) != 1)
                      throw new AssertionError("Emergency clearance drop missing");
                    fixture
                        .getLogger()
                        .info(
                            "NAVIGATION RECOVERY PASS: 12 queued maps; airborne and surface-water"
                                + " traversal; drowning escape; dirt suffocation clearance; both"
                                + " original repairs completed; exact plank/dirt conservation;"
                                + " travel="
                                + Arrays.toString(travel));
                    task.cancel();
                    Bukkit.getGlobalRegionScheduler()
                        .runDelayed(fixture, t -> Bukkit.shutdown(), 20);
                  } else if (now - started > 120000) {
                    throw new AssertionError(
                        "Physical deadline: "
                            + workers.stream().map(VillagerWorker::inspection).toList());
                  }
                } catch (Throwable error) {
                  task.cancel();
                  fail(error);
                }
              },
              1,
              1);
    } catch (Throwable error) {
      fail(error);
    }
  }

  private void fail(Throwable error) {
    fixture.getLogger().severe("NAVIGATION RECOVERY FAIL: " + error);
    error.printStackTrace();
    workers.forEach(VillagerWorker::stop);
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> Bukkit.shutdown(), 20);
  }
}
