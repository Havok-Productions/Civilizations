package test;

import com.google.gson.Gson;
import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.ai.*;
import dev.civilizations.core.*;
import dev.civilizations.learning.RecoveryDiagnostics;
import dev.civilizations.learning.RecoveryExperiments;
import dev.civilizations.world.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * One targeted physical case: a blocked shore, changed terrain, natural clearance and resumption.
 */
final class EmergencyRecoveryChecks {
  private final JavaPlugin fixture;
  private final List<VillagerWorker> workers = new ArrayList<>();
  private final List<Villager> actors = new ArrayList<>();
  private final List<Job> jobs = new ArrayList<>();
  private final Set<Integer> finished = new HashSet<>();
  private CivilizationsPlugin plugin;
  private Settlement village;
  private boolean diagnosed;
  private int y;
  private Path evidenceRoot;

  EmergencyRecoveryChecks(JavaPlugin fixture) {
    this.fixture = fixture;
  }

  void start() {
    Bukkit.getGlobalRegionScheduler()
        .runDelayed(
            fixture,
            t -> {
              World world = Bukkit.getWorlds().getFirst();
              world.setTime(1000);
              List<CompletableFuture<Void>> ready = new ArrayList<>();
              for (int x = -2; x <= 2; x++)
                for (int z = -2; z <= 2; z++) {
                  int cx = x, cz = z;
                  var call = new CompletableFuture<Void>();
                  ready.add(call);
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
              CompletableFuture.allOf(ready.toArray(CompletableFuture[]::new))
                  .thenRun(
                      () ->
                          Bukkit.getRegionScheduler()
                              .runDelayed(
                                  fixture, new Location(world, 0, 1, 0), q -> seed(world), 60));
            },
            60);
  }

  @SuppressWarnings("unchecked")
  private void seed(World world) {
    try {
      plugin = (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
      evidenceRoot =
          plugin.getDataFolder().toPath().resolve("CoreAI-emergency-fixture-" + UUID.randomUUID());
      ModelBackend offline =
          new ModelBackend() {
            public boolean ready() {
              return false;
            }

            public String status() {
              return "fixture offline";
            }

            public void close() {}

            public String complete(String system, String report) {
              throw new AssertionError("Unexpected model call");
            }
          };
      var service =
          new RecoveryExperiments(
              evidenceRoot,
              new InferenceQueue(offline, 2),
              () -> "offline",
              s -> fixture.getLogger().severe(s));
      var serviceField = CivilizationsPlugin.class.getDeclaredField("experiments");
      serviceField.setAccessible(true);
      serviceField.set(plugin, service);
      y = world.getHighestBlockYAt(0, 0) + 4;
      for (int x = -4; x <= 12; x++)
        for (int z = -3; z <= 10; z++)
          for (int dy = -3; dy <= 4; dy++)
            world.getBlockAt(x, y + dy, z).setType(dy < 0 ? Material.STONE : Material.AIR, false);
      // Lane 0: a fence extends above a nominally solid floor; west is a real dry exit.
      for (int x = 1; x <= 3; x++) world.getBlockAt(x, y - 1, 0).setType(Material.WATER, false);
      world.getBlockAt(4, y - 1, 0).setType(Material.OAK_FENCE, false);
      for (int dy = 0; dy <= 3; dy++) {
        world.getBlockAt(2, y + dy, 0).setType(Material.STONE, false);
        world.getBlockAt(3, y + dy, -1).setType(Material.STONE, false);
        world.getBlockAt(3, y + dy, 1).setType(Material.STONE, false);
      }
      // Lane 1: a shallow pool, three stone walls, and a two-block natural dirt bank to clear.
      for (int x = 1; x <= 3; x++) {
        world.getBlockAt(x, y - 1, 6).setType(Material.WATER, false);
        for (int dy = -1; dy <= 3; dy++) {
          world.getBlockAt(x, y + dy, 5).setType(Material.STONE, false);
          world.getBlockAt(x, y + dy, 7).setType(Material.STONE, false);
        }
      }
      for (int dy = -1; dy <= 3; dy++)
        world.getBlockAt(0, y + dy, 6).setType(Material.STONE, false);
      world.getBlockAt(4, y, 6).setType(Material.DIRT, false);
      world.getBlockAt(4, y + 1, 6).setType(Material.DIRT, false);
      var data = new Settlement.Data();
      data.world = world.getUID().toString();
      data.center = new Pos(6, y, 3);
      village = new Settlement(data);
      var vf = CivilizationsPlugin.class.getDeclaredField("settlements");
      vf.setAccessible(true);
      ((Map<String, Settlement>) vf.get(plugin)).put(village.id(), village);
      var wf = CivilizationsPlugin.class.getDeclaredField("workers");
      wf.setAccessible(true);
      var registry = (Map<String, VillagerWorker>) wf.get(plugin);
      var begin = VillagerWorker.class.getDeclaredMethod("begin", Job.class);
      begin.setAccessible(true);
      for (int i = 0; i < 2; i++) {
        int z = i * 6;
        Villager actor = world.spawn(new Location(world, 3.5, y - .9, z + .5), Villager.class);
        actor.setAdult();
        actor.setPersistent(true);
        actor.setInvulnerable(true);
        actor.getInventory().addItem(new ItemStack(Material.OAK_PLANKS, 2));
        actors.add(actor);
        village.enroll(actor.getUniqueId().toString(), 20);
        Job job =
            new Job(
                Job.Kind.PLACE,
                "emergency-fixture-" + i,
                new Pos(i == 0 ? -2 : 9, y, z),
                new Pos(i == 0 ? -1 : 8, y, z),
                "OAK_PLANKS",
                "AIR",
                null);
        jobs.add(job);
        village.addProject(job.project, List.of(job));
        var worker = new VillagerWorker(plugin, actor, village);
        workers.add(worker);
        registry.put(worker.id(), worker);
        village.claim(job.id, worker.id(), System.currentTimeMillis());
        begin.invoke(worker, job);
        // Enter the real emergency controller immediately, without waiting for actual damage.
        var ef = VillagerWorker.class.getDeclaredField("emergency");
        ef.setAccessible(true);
        Object emergency = ef.get(worker);
        var signal = emergency.getClass().getDeclaredMethod("signal", String.class);
        signal.setAccessible(true);
        signal.invoke(emergency, "DROWNING");
      }
      workers.forEach(VillagerWorker::start);
      long started = System.currentTimeMillis();
      List<Location> previous =
          new ArrayList<>(actors.stream().map(a -> a.getLocation().clone()).toList());
      Bukkit.getRegionScheduler()
          .runAtFixedRate(
              fixture,
              new Location(world, 3, y, 3),
              task -> {
                try {
                  String memory =
                      new Gson().toJson(plugin.experiments().diagnosticReport(village.id()));
                  if (!diagnosed
                      && memory.contains("emergency_navigation_stall")
                      && memory.contains("no_executed_surface_exit")) {
                    diagnosed = true;
                    // Open a previously blocked exit only after the persistent diagnosis arrives.
                    for (int dy = 0; dy <= 3; dy++)
                      world.getBlockAt(2, y + dy, 0).setType(Material.AIR, false);
                  }
                  for (int i = 0; i < 2; i++) {
                    var actor = actors.get(i);
                    var at = actor.getLocation();
                    if (!actor.isValid() || actor.isDead()) throw new AssertionError("Worker died");
                    if (at.distance(previous.get(i)) > 2)
                      throw new AssertionError("Unexpected position jump");
                    previous.set(i, at.clone());
                    if (jobs.get(i).complete && finished.add(i)) workers.get(i).stop();
                  }
                  if (finished.size() == 2) {
                    if (!diagnosed) throw new AssertionError("Stall was never diagnosed");
                    if (world.getBlockAt(4, y - 1, 0).getType() != Material.OAK_FENCE)
                      throw new AssertionError("Fence was changed");
                    if (world.getBlockAt(4, y, 6).getType() != Material.AIR
                        || world.getBlockAt(4, y + 1, 6).getType() != Material.AIR)
                      throw new AssertionError("Dirt bank was not physically cleared");
                    for (int i = 0; i < 2; i++) {
                      if (InventoryOps.count(actors.get(i).getInventory(), Material.OAK_PLANKS)
                          != 1) throw new AssertionError("Plank conservation mismatch");
                      var p = jobs.get(i).target;
                      if (world.getBlockAt(p.x(), p.y(), p.z()).getType() != Material.OAK_PLANKS)
                        throw new AssertionError("Original repair not executed");
                    }
                    if (InventoryOps.count(actors.get(1).getInventory(), Material.DIRT) != 2)
                      throw new AssertionError("Dirt drops missing");
                    task.cancel();
                    Bukkit.getAsyncScheduler()
                        .runNow(
                            fixture,
                            t -> {
                              try {
                                Path root = evidenceRoot;
                                String persisted =
                                    new Gson()
                                        .toJson(new RecoveryDiagnostics(root).report(village.id()));
                                if (!persisted.contains("emergency_navigation_stall"))
                                  throw new AssertionError("Diagnosis did not survive reload");
                                String journal =
                                    Files.readString(root.resolve("data/agents.jsonl"));
                                if (!journal.contains("emergency_escaped")
                                    || !journal.contains("emergency_clearance"))
                                  throw new AssertionError("Missing measured outcomes");
                                plugin.coreAi().movement().flush().get(10, TimeUnit.SECONDS);
                                Path movements =
                                    plugin
                                        .getDataFolder()
                                        .toPath()
                                        .resolve("CoreAI/movement/attempts.jsonl");
                                var receipts =
                                    Files.readAllLines(movements).stream()
                                        .map(
                                            line ->
                                                new Gson()
                                                    .fromJson(
                                                        line,
                                                        dev.coreai.movement.MovementLearning.Receipt
                                                            .class))
                                        .filter(
                                            r ->
                                                workers.stream()
                                                    .anyMatch(w -> w.id().equals(r.worker())))
                                        .toList();
                                var terminal =
                                    receipts.stream()
                                        .filter(r -> r.phase().equals("finished"))
                                        .toList();
                                if (terminal.stream()
                                    .noneMatch(r -> Boolean.TRUE.equals(r.sample().success())))
                                  throw new AssertionError("No actual movement arrival label");
                                if (receipts.stream()
                                    .anyMatch(r -> !r.sample().source().equals("physical")))
                                  throw new AssertionError("Simulated label in physical journal");
                                if (terminal.stream()
                                    .anyMatch(
                                        end ->
                                            receipts.stream()
                                                .noneMatch(
                                                    start ->
                                                        start.phase().equals("started")
                                                            && start
                                                                .sample()
                                                                .id()
                                                                .equals(end.sample().id())
                                                            && start
                                                                .sample()
                                                                .features()
                                                                .equals(end.sample().features()))))
                                  throw new AssertionError(
                                      "Arrival missing its original pre-action features");
                                fixture
                                    .getLogger()
                                    .info(
                                        "MOVEMENT RECEIPTS PASS: "
                                            + terminal.size()
                                            + " measured outcomes; "
                                            + terminal.stream()
                                                .filter(
                                                    r -> Boolean.TRUE.equals(r.sample().success()))
                                                .count()
                                            + " arrivals; pre-action features matched");
                                fixture
                                    .getLogger()
                                    .info(
                                        "EMERGENCY RECOVERY PASS: blocked shore diagnosed and"
                                            + " changed exit reobserved; fence retained; natural"
                                            + " dirt bank cleared; two original repairs completed;"
                                            + " exact plank/dirt conservation; diagnosis reloaded;"
                                            + " no model calls");
                                Bukkit.getGlobalRegionScheduler()
                                    .runDelayed(fixture, t2 -> Bukkit.shutdown(), 20);
                              } catch (Throwable e) {
                                fail(e);
                              }
                            });
                  } else if (System.currentTimeMillis() - started > 90000)
                    throw new AssertionError(
                        "Deadline: " + workers.stream().map(VillagerWorker::inspection).toList());
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

  private void fail(Throwable e) {
    fixture.getLogger().severe("EMERGENCY RECOVERY FAIL: " + e);
    e.printStackTrace();
    workers.forEach(VillagerWorker::stop);
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> Bukkit.shutdown(), 20);
  }
}
