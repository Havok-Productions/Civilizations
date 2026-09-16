package test;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import dev.civilizations.design.*;
import dev.civilizations.world.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** One changed-behavior check: surface detour, retained mining/repair, shifted complete wall. */
final class AdaptiveRecoveryChecks {
  private final JavaPlugin fixture;
  private World world;
  private CivilizationsPlugin plugin;
  private final List<VillagerWorker> workers = new ArrayList<>();
  private final List<Villager> actors = new ArrayList<>();
  private final List<Location> previous = new ArrayList<>();
  private final List<Settlement> villages = new ArrayList<>();
  private final Set<Integer> finished = new HashSet<>();
  private int y;
  private long started;
  private double distance;
  private List<Job> wallJobs;
  private Job mine, repair;

  AdaptiveRecoveryChecks(JavaPlugin fixture) {
    this.fixture = fixture;
  }

  void start() {
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> prepare(), 60);
  }

  private void prepare() {
    world = Bukkit.getWorlds().getFirst();
    world.setTime(1000);
    List<CompletableFuture<Void>> calls = new ArrayList<>();
    for (int x = -2; x <= 3; x++)
      for (int z = -2; z <= 3; z++) {
        int cx = x, cz = z;
        var call = new CompletableFuture<Void>();
        calls.add(call);
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
                              call.complete(null);
                            }))
            .exceptionally(
                e -> {
                  call.completeExceptionally(e);
                  return null;
                });
      }
    CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new))
        .thenRun(
            () ->
                Bukkit.getRegionScheduler()
                    .runDelayed(fixture, new Location(world, 16, 80, 16), t -> seed(), 60))
        .exceptionally(
            e -> {
              fail(e);
              return null;
            });
  }

  @SuppressWarnings("unchecked")
  private VillagerWorker worker(Pos origin, Location at) throws Exception {
    var data = new Settlement.Data();
    data.world = world.getUID().toString();
    data.center = origin;
    Settlement village = new Settlement(data);
    villages.add(village);
    var field = CivilizationsPlugin.class.getDeclaredField("settlements");
    field.setAccessible(true);
    ((Map<String, Settlement>) field.get(plugin)).put(village.id(), village);
    Villager actor = world.spawn(at, Villager.class);
    actor.setAdult();
    actor.setPersistent(true);
    actors.add(actor);
    previous.add(at.clone());
    village.enroll(actor.getUniqueId().toString(), 20);
    var worker = new VillagerWorker(plugin, actor, village);
    workers.add(worker);
    field = CivilizationsPlugin.class.getDeclaredField("workers");
    field.setAccessible(true);
    ((Map<String, VillagerWorker>) field.get(plugin)).put(worker.id(), worker);
    return worker;
  }

  private void seed() {
    try {
      plugin = (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
      y = world.getHighestBlockYAt(16, 16) + 5;
      if (!Bukkit.isOwnedByCurrentRegion(new Location(world, 24, y, 20), 3))
        throw new AssertionError("fixture region unavailable");
      for (int x = -3; x <= 44; x++)
        for (int z = -3; z <= 36; z++)
          for (int dy = -4; dy <= 8; dy++)
            world
                .getBlockAt(x, y + dy, z)
                .setType(
                    dy < -1 ? Material.STONE : dy == -1 ? Material.GRASS_BLOCK : Material.AIR,
                    false);
      // Deep pool, with a central obstacle that makes a surface detour necessary.
      for (int x = 0; x <= 8; x++)
        for (int z = -1; z <= 3; z++)
          for (int dy = -3; dy <= -1; dy++)
            world.getBlockAt(x, y + dy, z).setType(Material.WATER, false);
      for (int x = -1; x <= 8; x++)
        for (int z : new int[] {-2, 4})
          for (int dy = -1; dy <= 2; dy++)
            world.getBlockAt(x, y + dy, z).setType(Material.STONE, false);
      for (int x = 5; x <= 6; x++)
        for (int z = -1; z <= 1; z++)
          for (int dy = -3; dy <= 2; dy++)
            world.getBlockAt(x, y + dy, z).setType(Material.STONE, false);
      for (int z = -1; z <= 3; z++)
        for (int dy = -1; dy <= 2; dy++)
          world.getBlockAt(-1, y + dy, z).setType(Material.STONE, false);
      for (int x = 27; x <= 44; x++)
        for (int z = 5; z <= 36; z++) world.getBlockAt(x, y - 1, z).setType(Material.WATER, false);
      var swimmer = worker(new Pos(12, y, 0), new Location(world, 2.5, y - 3, .5));
      actors.getFirst().setRemainingAir(20);
      actors
          .getFirst()
          .getInventory()
          .addItem(new ItemStack(Material.WOODEN_PICKAXE), new ItemStack(Material.OAK_PLANKS, 2));
      Pos target = new Pos(12, y, 0), stand = new Pos(11, y, 0);
      world.getBlockAt(target.x(), target.y(), target.z()).setType(Material.STONE, false);
      mine = new Job(Job.Kind.MINE, "design-escape-repair", target, stand, "", "STONE", null);
      repair = new Job(Job.Kind.PLACE, mine.project, target, stand, "OAK_PLANKS", "AIR", null);
      repair.phase = 1;
      villages.getFirst().addProject(mine.project, List.of(mine, repair));
      var builder = worker(new Pos(24, y, 20), new Location(world, 24.5, y, 20.5));
      var village = villages.get(1);
      Terrain terrain =
          new Terrain() {
            public int height(int x, int z) {
              return y - 1;
            }

            public boolean available(int x, int z) {
              return x >= -3 && x <= 44 && z >= -3 && z <= 36;
            }

            public String type(Pos p) {
              if (!available(p.x(), p.z())
                  || !Bukkit.isOwnedByCurrentRegion(new Location(world, p.x(), p.y(), p.z())))
                return "UNKNOWN";
              return world.getBlockAt(p.x(), p.y(), p.z()).getType().name();
            }
          };
      var examples = SiteObservations.candidates(terrain, village, p -> false, Set.of("wall"));
      if (examples.isEmpty()) throw new AssertionError("no shifted wall alternative");
      Blueprint wall = (Blueprint) examples.getFirst().get("blueprint");
      // Two layers exercise the whole contour and gate while keeping this executor check short.
      wall = new Blueprint(wall.kind(), wall.purpose(), wall.x(), wall.z(), wall.width(),
          wall.depth(), 2, wall.direction(), wall.points());
      var saved =
          DesignProposals.retain(village, wall, village.center(), System.currentTimeMillis());
      var admission = DesignProposals.admit(village, saved, terrain, p -> false, 2, 0);
      if (!admission.accepted()) throw new AssertionError(admission.proposal().reason());
      wallJobs = admission.compiled().jobs();
      actors
          .get(1)
          .getInventory()
          .addItem(
              new ItemStack(Material.COBBLESTONE, 64),
              new ItemStack(Material.COBBLESTONE, 32),
              new ItemStack(Material.OAK_FENCE_GATE));
      fixture
          .getLogger()
          .info("ADAPTIVE RECOVERY wall admitted: " + wall + "; actions=" + wallJobs.size());
      workers.forEach(VillagerWorker::start);
      swimmer.probe(
          "start", mine.id, 120000, lines -> fixture.getLogger().info("SWIM PROBE " + lines));
      builder.probe(
          "start",
          wallJobs.getFirst().id,
          120000,
          lines -> fixture.getLogger().info("WALL PROBE " + lines));
      started = System.currentTimeMillis();
      Bukkit.getRegionScheduler()
          .runAtFixedRate(
              fixture,
              new Location(world, 16, y, 16),
              task -> {
                try {
                  for (int i = 0; i < actors.size(); i++) {
                    var actor = actors.get(i);
                    if (!actor.isValid() || actor.isDead()) throw new AssertionError("worker died");
                    double delta = actor.getLocation().distance(previous.get(i));
                    if (delta > 1.8) throw new AssertionError("nonphysical position jump " + delta);
                    if (i == 0) distance += delta;
                    previous.set(i, actor.getLocation().clone());
                    boolean assignedComplete = i == 0 ? mine.complete && repair.complete
                        : wallJobs.stream().allMatch(j -> j.complete);
                    if (assignedComplete && finished.add(i)) workers.get(i).stop();
                  }
                  if (finished.size() == 2) {
                    if (!mine.complete
                        || !repair.complete
                        || world.getBlockAt(12, y, 0).getType() != Material.OAK_PLANKS
                        || InventoryOps.count(
                                actors.getFirst().getInventory(), Material.COBBLESTONE)
                            != 1
                        || InventoryOps.count(actors.getFirst().getInventory(), Material.OAK_PLANKS)
                            != 1
                        || distance < 8)
                      throw new AssertionError("escape/mining/repair conservation mismatch");
                    for (Job job : wallJobs)
                      if (world.getBlockAt(job.target.x(), job.target.y(), job.target.z()).getType()
                          != Material.valueOf(job.material))
                        throw new AssertionError(
                            "wall job marked done without block " + job.target);
                    long stone =
                        wallJobs.stream().filter(j -> j.material.equals("COBBLESTONE")).count();
                    if (InventoryOps.count(actors.get(1).getInventory(), Material.COBBLESTONE)
                            != 96 - stone
                        || InventoryOps.count(actors.get(1).getInventory(), Material.OAK_FENCE_GATE)
                            != 0) throw new AssertionError("wall material conservation mismatch");
                    fixture
                        .getLogger()
                        .info(
                            "ADAPTIVE RECOVERY PASS: physical surface detour, resumed mining and"
                                + " repair, complete shifted wall="
                                + wallJobs.size()
                                + " blocks; exact inventories; swim travel="
                                + distance);
                    task.cancel();
                    Bukkit.getGlobalRegionScheduler()
                        .runDelayed(fixture, t -> Bukkit.shutdown(), 20);
                  } else if (System.currentTimeMillis() - started > 180000)
                    throw new AssertionError(
                        "deadline: " + workers.stream().map(VillagerWorker::inspection).toList());
                } catch (Throwable e) {
                  task.cancel();
                  fail(e);
                }
              },
              1,
              1);
    } catch (Throwable e) {
      fail(e);
    }
  }

  private void fail(Throwable error) {
    fixture.getLogger().severe("ADAPTIVE RECOVERY FAIL: " + error);
    error.printStackTrace();
    workers.forEach(VillagerWorker::stop);
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> Bukkit.shutdown(), 20);
  }
}
