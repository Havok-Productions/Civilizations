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

/**
 * One physical case for reserved supplies, cooperative movement, structural repair and real drops.
 */
final class ProgressLearningChecks {
  private final JavaPlugin fixture;
  private World world;
  private CivilizationsPlugin plugin;
  private Settlement village;
  private int y;
  private final List<VillagerWorker> workers = new ArrayList<>();
  private final List<Villager> actors = new ArrayList<>();
  private final List<Job> jobs = new ArrayList<>();
  private Villager blocker, harvester;
  private GatheringActions gathering;
  private WorkMovementControl harvestMovement;
  private long started;
  private boolean yielded, cached, supplyCompleted, returnedFromElsewhere;
  private int harvestStage;

  ProgressLearningChecks(JavaPlugin fixture) {
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
        for (int z = -4; z <= 10; z++)
          for (int dy = -1; dy <= 4; dy++)
            world.getBlockAt(x, y + dy, z).setType(dy == -1 ? Material.STONE : Material.AIR, false);
      var data = new Settlement.Data();
      data.world = world.getUID().toString();
      data.center = new Pos(15, y, 0);
      data.chest = new Pos(10, y, 0);
      block(data.chest).setType(Material.CHEST, false);
      var chest = (org.bukkit.block.Chest) block(data.chest).getState();
      chest.getBlockInventory().addItem(new ItemStack(Material.BREAD, 3));
      village = new Settlement(data);
      var field = CivilizationsPlugin.class.getDeclaredField("settlements");
      field.setAccessible(true);
      ((Map<String, Settlement>) field.get(plugin)).put(village.id(), village);

      // No capacity and no natural wood: the next job must recover its reserved wood bundle.
      Pos dirt = new Pos(0, y, 0);
      block(dirt).setType(Material.DIRT, false);
      Job clear =
          new Job(Job.Kind.CLEAR, "reserved-project", dirt, dirt.add(-1, 0, 0), "", "DIRT", null);
      Material[] carried = {
        Material.OAK_LOG,
        Material.GRANITE,
        Material.DIORITE,
        Material.ANDESITE,
        Material.TUFF,
        Material.CALCITE,
        Material.GRAVEL,
        Material.STONE
      };
      Job paid =
          new Job(
              Job.Kind.PLACE,
              clear.project,
              new Pos(2, y, 0),
              new Pos(1, y, 0),
              "OAK_PLANKS",
              "AIR",
              null);
      paid.phase = 1;
      attach(dirt.add(-2, 0, 0), clear, paid);
      for (int i = 0; i < 8; i++)
        actors.getFirst().getInventory().setItem(i, new ItemStack(carried[i], 64));

      Pos foot = new Pos(20, y, 0), head = foot.add(1, 0, 0), oldHead = foot.add(0, 0, -1);
      block(foot)
          .setBlockData(
              Bukkit.createBlockData("minecraft:white_bed[facing=north,part=foot]"), false);
      block(oldHead)
          .setBlockData(
              Bukkit.createBlockData("minecraft:white_bed[facing=north,part=head]"), false);
      Job bed =
          new Job(
              Job.Kind.PLACE,
              "reorient-bed",
              foot,
              foot.add(-1, 0, 0),
              "WHITE_BED",
              "AIR",
              "minecraft:white_bed[facing=east,part=foot]");
      attach(
          foot.add(-2, 0, 0), bed); // Empty inventory: reuse the existing bed without duplication.
      Job own =
          new Job(
              Job.Kind.PLACE,
              "yield-retain",
              new Pos(25, y, 0),
              new Pos(24, y, 0),
              "OAK_PLANKS",
              "AIR",
              null);
      attach(head, own);
      blocker = actors.getLast();
      blocker.setAI(false);
      blocker.getInventory().addItem(new ItemStack(Material.OAK_PLANKS));

      Pos gatePos = new Pos(30, y, 0);
      block(gatePos)
          .setBlockData(Bukkit.createBlockData("minecraft:oak_fence_gate[facing=east]"), false);
      Job gate =
          new Job(
              Job.Kind.PLACE,
              "correct-gate",
              gatePos,
              gatePos.add(-1, 0, 0),
              "OAK_FENCE_GATE",
              "AIR",
              "minecraft:oak_fence_gate[facing=north]");
      attach(gatePos.add(-2, 0, 0), gate);

      harvester = world.spawn(at(new Pos(38, y, 5)), Villager.class);
      harvester.setAdult();
      village.enroll(harvester.getUniqueId().toString(), 20);
      var registry = CivilizationsPlugin.class.getDeclaredField("workers");
      registry.setAccessible(true);
      ((Map<String, VillagerWorker>) registry.get(plugin))
          .put(harvester.getUniqueId().toString(), new VillagerWorker(plugin, harvester, village));
      harvester.getInventory().addItem(new ItemStack(Material.STONE_PICKAXE));
      harvestMovement = new WorkMovementControl(harvester, fixture.getLogger()::warning);
      var nav =
          new WorkerNavigation(
              plugin,
              harvester,
              village,
              (now, reason) -> {
                throw new AssertionError(reason);
              });
      gathering =
          new GatheringActions(
              plugin,
              harvester,
              village,
              nav,
              new RecoveryPolicy(System.currentTimeMillis(), 60000),
              (now, reason) -> {
                throw new AssertionError(reason);
              });
      block(new Pos(40, y, 5)).setType(Material.IRON_ORE, false);
      block(new Pos(40, y, 7)).setType(Material.CLAY, false);
      block(new Pos(38, y, 7)).setType(Material.SUGAR_CANE, false);
      block(new Pos(38, y + 1, 7)).setType(Material.SUGAR_CANE, false);
      started = System.currentTimeMillis();
      for (int i = 0; i < workers.size(); i++) if (i != 2) workers.get(i).start();
      fixture
          .getLogger()
          .info(
              "PROGRESS LEARNING SEEDED: full inventory; occupied reorientation; gate correction;"
                  + " ore/clay/cane harvest");
      Bukkit.getRegionScheduler()
          .runAtFixedRate(
              fixture,
              at(new Pos(10, y, 0)),
              task -> {
                try {
                  long now = System.currentTimeMillis();
                  if (!village.caches().isEmpty()) cached = true;
                  if (!returnedFromElsewhere
                      && village.jobs().stream()
                          .anyMatch(j -> j.id.equals(clear.id) && j.complete)) {
                    returnedFromElsewhere = true;
                    actors.getFirst().teleportAsync(at(new Pos(5, y, 3)));
                  }
                  if (!yielded && village.yielding().incoming(workers.get(2).id(), now) != null) {
                    require(
                        !own.complete && workers.get(2).id().equals(own.owner),
                        "Blocker lost its own claim before yielding");
                    blocker.setAI(true);
                    workers.get(2).start();
                    yielded = true;
                  }
                  for (int i = 0; i < workers.size(); i++) {
                    require(
                        actors.get(i).isValid() && actors.get(i).getHealth() == 20,
                        "Worker injured");
                    require(jobs.get(i).failures == 0, "Task failed: " + jobs.get(i).blockedReason);
                    if (village.allComplete(jobs.get(i).project)) workers.get(i).stop();
                  }
                  if (!supplyCompleted && village.allComplete(clear.project)) {
                    supplyCompleted = true;
                    require(
                        cached
                            && block(dirt).getType().isAir()
                            && block(paid.target).getType() == Material.OAK_PLANKS,
                        "Reserved-supply project incomplete");
                    Map<String, Integer> total =
                        new HashMap<>(InventoryOps.summary(actors.getFirst().getInventory()));
                    for (var cache : village.caches())
                      if (cache.project().equals(clear.project)) {
                        var item =
                            (org.bukkit.entity.Item)
                                world.getEntity(UUID.fromString(cache.entity()));
                        require(
                            item != null && item.isValid() && item.isUnlimitedLifetime(),
                            "Reserved bundle missing");
                        require(
                            !item.canMobPickup() && !item.canPlayerPickup(),
                            "Reserved bundle exposed to automatic pickup");
                        total.merge(
                            item.getItemStack().getType().name(),
                            item.getItemStack().getAmount(),
                            Integer::sum);
                      }
                    require(
                        total.getOrDefault("OAK_LOG", 0) == 63
                            && total.getOrDefault("OAK_PLANKS", 0) == 3
                            && total.getOrDefault("DIRT", 0) == 1,
                        "Project material conservation: " + total);
                    for (Material m : carried)
                      if (m != Material.OAK_LOG)
                        require(
                            total.getOrDefault(m.name(), 0) == 64, "Lost cached material: " + m);
                  }
                  require(
                      InventoryOps.summary(chest.getBlockInventory()).equals(Map.of("BREAD", 3)),
                      "Unfinished project leaked into community stock");
                  if (harvestStage < 3) {
                    harvestMovement.working(true);
                    String wanted =
                        List.of("RAW_IRON", "CLAY_BALL", "SUGAR_CANE").get(harvestStage);
                    var loc = harvester.getLocation();
                    gathering.gather(
                        wanted, now, new Pos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
                    if (InventoryOps.count(harvester.getInventory(), Material.valueOf(wanted))
                        > 0) {
                      harvestStage++;
                      gathering.reset();
                    }
                  }
                  if (supplyCompleted
                      && jobs.stream().allMatch(j -> village.allComplete(j.project))
                      && harvestStage == 3) {
                    require(
                        yielded
                            && WorkState.satisfied(bed, block(foot))
                            && block(oldHead).getType().isAir(),
                        "Bed was not reoriented after cooperative yield");
                    require(WorkState.satisfied(gate, block(gatePos)), "Gate facing unchanged");
                    require(
                        InventoryOps.total(actors.get(1).getInventory()) == 0
                            && InventoryOps.total(actors.get(3).getInventory()) == 0,
                        "Structural repair duplicated items");
                    require(
                        InventoryOps.count(blocker.getInventory(), Material.OAK_PLANKS) == 0
                            && block(own.target).getType() == Material.OAK_PLANKS,
                        "Yielded worker did not finish its original job");
                    require(
                        InventoryOps.count(harvester.getInventory(), Material.RAW_IRON) == 1
                            && InventoryOps.count(harvester.getInventory(), Material.CLAY_BALL)
                                == 4,
                        "Actual ore/clay drops mismatch");
                    require(
                        block(new Pos(38, y, 7)).getType() == Material.SUGAR_CANE
                            && block(new Pos(38, y + 1, 7)).getType().isAir(),
                        "Renewable cane base not preserved");
                    fixture
                        .getLogger()
                        .info(
                            "PROGRESS LEARNING PASS: reserved wood recovered after full-inventory"
                                + " clearance; exact item conservation; no community deposit;"
                                + " blocker yielded and completed own job; existing bed/gate"
                                + " corrected without new items; real iron/clay drops; cane base"
                                + " retained. Policy comparisons are separately verified JVM"
                                + " contracts; no neural training claimed.");
                    task.cancel();
                    harvestMovement.working(false);
                    Bukkit.getGlobalRegionScheduler()
                        .runDelayed(fixture, t -> Bukkit.shutdown(), 20);
                  } else if (now - started > 90000)
                    throw new AssertionError(
                        "Deadline: projects="
                            + jobs.stream()
                                .map(j -> j.project + "=" + village.allComplete(j.project))
                                .toList()
                            + "; supplies="
                            + supplyCompleted
                            + "; "
                            + workers.stream().map(VillagerWorker::inspection).toList()
                            + " harvest="
                            + harvestStage);
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
  private VillagerWorker attach(Pos at, Job job, Job... following) throws Exception {
    Villager actor = world.spawn(at(at), Villager.class);
    actor.setAdult();
    actor.setPersistent(true);
    village.enroll(actor.getUniqueId().toString(), 20);
    List<Job> project = new ArrayList<>(List.of(job));
    project.addAll(List.of(following));
    require(village.addProject(job.project, project), "project admission failed");
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
    fixture.getLogger().severe("PROGRESS LEARNING FAIL: " + error);
    error.printStackTrace();
    workers.forEach(VillagerWorker::stop);
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> Bukkit.shutdown(), 20);
  }
}
