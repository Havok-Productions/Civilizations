package test;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import dev.civilizations.world.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.*;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** One disposable-world check of no-op work, collision waits, and resumed paid construction. */
final class ConstructionGuardChecks {
  private final JavaPlugin fixture;
  private World world;
  private CivilizationsPlugin plugin;
  private Settlement village;
  private int y;
  private final List<VillagerWorker> workers = new ArrayList<>();
  private final List<Villager> actors = new ArrayList<>();
  private final List<Job> jobs = new ArrayList<>();
  private Villager blocker;
  private Location initial;
  private long started;
  private boolean released, moving;

  ConstructionGuardChecks(JavaPlugin fixture) {
    this.fixture = fixture;
  }

  void start() {
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> prepare(), 60);
  }

  private void prepare() {
    world = Bukkit.getWorlds().getFirst();
    world.setTime(1000);
    List<CompletableFuture<Void>> chunks = new ArrayList<>();
    for (int x = -2; x <= 4; x++)
      for (int z = -2; z <= 2; z++) {
        int cx = x, cz = z;
        var loaded = new CompletableFuture<Void>();
        chunks.add(loaded);
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
                              loaded.complete(null);
                            }))
            .exceptionally(
                error -> {
                  loaded.completeExceptionally(error);
                  return null;
                });
      }
    CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new))
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
      require(
          Bukkit.isOwnedByCurrentRegion(new Location(world, 32, y, 0), 2), "region unavailable");
      for (int x = -4; x <= 50; x++)
        for (int z = -4; z <= 4; z++)
          for (int dy = -1; dy <= 4; dy++)
            world.getBlockAt(x, y + dy, z).setType(dy == -1 ? Material.STONE : Material.AIR, false);
      var data = new Settlement.Data();
      data.world = world.getUID().toString();
      data.center = new Pos(15, y, 0);
      village = new Settlement(data);
      var field = CivilizationsPlugin.class.getDeclaredField("settlements");
      field.setAccessible(true);
      ((Map<String, Settlement>) field.get(plugin)).put(village.id(), village);

      // Actual production tick, empty inventory: neither tools nor supplies may be requested.
      for (int i = 0; i < 3; i++) {
        Pos target = new Pos(38 + i * 4, y, 0);
        Job.Kind kind = i == 1 ? Job.Kind.MINE : Job.Kind.PLACE;
        Job job =
            new Job(
                kind,
                "already-done-" + i,
                target,
                target.add(-1, 0, 0),
                i == 1 ? "" : "OAK_PLANKS",
                i == 1 ? "STONE" : "AIR",
                null);
        if (kind == Job.Kind.PLACE) block(target).setType(Material.OAK_PLANKS, false);
        VillagerWorker worker = attach(target.add(-2, 0, 0), job);
        if (i == 2) {
          var mode = VillagerWorker.class.getDeclaredField("mode");
          mode.setAccessible(true);
          mode.set(worker, "gather");
        }
        var tick = VillagerWorker.class.getDeclaredMethod("tickActive");
        tick.setAccessible(true);
        tick.invoke(worker);
        require(job.complete && job.failures == 0, "Already-satisfied job did not finish " + i);
        require(
            InventoryOps.summary(actors.getLast().getInventory()).isEmpty(),
            "Unneeded items fetched");
        require(worker.requests().isEmpty(), "Completed work published supplies");
        worker.stop();
      }
      workers.clear();
      actors.clear();
      jobs.clear();

      Pos ownTarget = new Pos(0, y, 0);
      Job own =
          new Job(
              Job.Kind.PLACE,
              "step-away",
              ownTarget,
              ownTarget.add(-1, 0, 0),
              "OAK_PLANKS",
              "AIR",
              null);
      attach(ownTarget, own);
      actors.getFirst().getInventory().addItem(new ItemStack(Material.OAK_PLANKS, 2));
      initial = actors.getFirst().getLocation().clone();
      require(
          PlacementSpace.obstruction(block(ownTarget), Material.OAK_PLANKS.createBlockData())
              != null,
          "Self collision not detected");
      require(
          PlacementSpace.obstruction(block(ownTarget), Material.TORCH.createBlockData()) == null,
          "Non-colliding torch incorrectly blocked");

      Pos foot = new Pos(20, y, 0), head = foot.add(1, 0, 0);
      Bed bed = (Bed) Bukkit.createBlockData("minecraft:white_bed[facing=east,part=foot]");
      Job bedJob =
          new Job(
              Job.Kind.PLACE,
              "occupied-bed-head",
              foot,
              foot.add(-1, 0, 0),
              "WHITE_BED",
              "AIR",
              bed.getAsString());
      attach(foot.add(-2, 0, 0), bedJob);
      actors.getLast().getInventory().addItem(new ItemStack(Material.WHITE_BED));
      blocker = world.spawn(at(head), Villager.class);
      blocker.setAdult();
      blocker.setAI(false);
      blocker.setGravity(false);

      // Also exercise the last placement barrier directly, after the worker's first check.
      var obstruction = new AtomicReference<PlacementSpace.Obstruction>();
      new BuildingActions(
              plugin,
              actors.getLast(),
              (now, reason) -> {
                throw new AssertionError(reason);
              },
              now -> {
                throw new AssertionError("Occupied bed completed");
              },
              (now, occupied) -> obstruction.set(occupied))
          .execute(bedJob, block(foot), System.currentTimeMillis());
      require(
          obstruction.get() != null && obstruction.get().block().equals(head),
          "Bed head not protected");
      require(block(foot).getType().isAir() && block(head).getType().isAir(), "Partial bed placed");
      require(
          InventoryOps.count(actors.getLast().getInventory(), Material.WHITE_BED) == 1,
          "Blocked placement consumed bed");
      started = System.currentTimeMillis();
      workers.forEach(VillagerWorker::start);
      Bukkit.getRegionScheduler()
          .runAtFixedRate(
              fixture,
              at(new Pos(10, y, 0)),
              task -> {
                try {
                  long now = System.currentTimeMillis();
                  for (int i = 0; i < workers.size(); i++) {
                    require(
                        actors.get(i).isValid()
                            && !actors.get(i).isDead()
                            && actors.get(i).getHealth() == 20,
                        "Worker injured during placement");
                    require(jobs.get(i).failures == 0, "Collision wait failed the task");
                    if (jobs.get(i).complete) workers.get(i).stop();
                  }
                  if (actors.getFirst().getLocation().distanceSquared(initial) > .36) moving = true;
                  if (!released && now - started >= 2500) {
                    require(
                        !bedJob.complete
                            && block(foot).getType().isAir()
                            && block(head).getType().isAir(),
                        "Occupied bed space mutated");
                    require(
                        InventoryOps.count(actors.getLast().getInventory(), Material.WHITE_BED)
                            == 1,
                        "Waiting worker lost bed");
                    blocker.remove();
                    released = true;
                  }
                  if (jobs.stream().allMatch(j -> j.complete)) {
                    require(released && moving, "No real wait or step-away movement");
                    require(block(ownTarget).getType() == Material.OAK_PLANKS, "Plank not placed");
                    require(
                        InventoryOps.count(actors.getFirst().getInventory(), Material.OAK_PLANKS)
                            == 1,
                        "Plank conservation");
                    require(
                        InventoryOps.count(actors.getLast().getInventory(), Material.WHITE_BED)
                            == 0,
                        "Bed not consumed exactly once");
                    require(
                        block(foot).getBlockData() instanceof Bed f
                            && f.getPart() == Bed.Part.FOOT
                            && block(head).getBlockData() instanceof Bed h
                            && h.getPart() == Bed.Part.HEAD,
                        "Bed halves missing");
                    fixture
                        .getLogger()
                        .info(
                            "CONSTRUCTION GUARDS PASS: 3 already-satisfied jobs with empty"
                                + " inventories; builder stepped out before placing; occupied bed"
                                + " head retained job/materials then resumed; exact plank/bed"
                                + " conservation; no injuries or task failures");
                    task.cancel();
                    Bukkit.getGlobalRegionScheduler()
                        .runDelayed(fixture, t -> Bukkit.shutdown(), 20);
                  } else if (now - started > 60000)
                    throw new AssertionError(
                        "Deadline " + workers.stream().map(VillagerWorker::inspection).toList());
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

  @SuppressWarnings("unchecked")
  private VillagerWorker attach(Pos at, Job job) throws Exception {
    Villager actor = world.spawn(at(at), Villager.class);
    actor.setAdult();
    actor.setPersistent(true);
    village.enroll(actor.getUniqueId().toString(), 20);
    village.addProject(job.project, List.of(job));
    VillagerWorker worker = new VillagerWorker(plugin, actor, village);
    var registry = CivilizationsPlugin.class.getDeclaredField("workers");
    registry.setAccessible(true);
    ((Map<String, VillagerWorker>) registry.get(plugin)).put(worker.id(), worker);
    require(village.claim(job.id, worker.id(), System.currentTimeMillis()), "claim failed");
    var begin = VillagerWorker.class.getDeclaredMethod("begin", Job.class);
    begin.setAccessible(true);
    begin.invoke(worker, job);
    actors.add(actor);
    workers.add(worker);
    jobs.add(job);
    return worker;
  }

  private Location at(Pos p) {
    return new Location(world, p.x() + .5, p.y(), p.z() + .5);
  }

  private org.bukkit.block.Block block(Pos p) {
    return world.getBlockAt(p.x(), p.y(), p.z());
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private void fail(Throwable error) {
    fixture.getLogger().severe("CONSTRUCTION GUARDS FAIL: " + error);
    error.printStackTrace();
    workers.forEach(VillagerWorker::stop);
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> Bukkit.shutdown(), 20);
  }
}
