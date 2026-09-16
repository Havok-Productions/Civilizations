package test;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.ai.*;
import dev.civilizations.core.*;
import dev.civilizations.learning.RecoveryExperiments;
import dev.civilizations.world.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.*;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** One crop corridor, then the same work after reloading the persisted lesson service. */
final class LessonMemoryChecks {
  private final JavaPlugin fixture;
  private CivilizationsPlugin plugin;
  private World world;
  private Settlement village;
  private RecoveryExperiments learning;
  private InferenceQueue queue;
  private Path root;
  private int y, phase;
  private final AtomicInteger calls = new AtomicInteger();

  LessonMemoryChecks(JavaPlugin fixture) {
    this.fixture = fixture;
  }

  void start() {
    Bukkit.getGlobalRegionScheduler()
        .runDelayed(
            fixture,
            task -> {
              plugin = (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
              world = Bukkit.getWorlds().getFirst();
              world.setTime(1000);
              var waits = new ArrayList<CompletableFuture<Void>>();
              for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++) {
                  int cx = x, cz = z;
                  var ready = new CompletableFuture<Void>();
                  waits.add(ready);
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
                                        ready.complete(null);
                                      }));
                }
              CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new))
                  .thenRun(
                      () ->
                          Bukkit.getRegionScheduler()
                              .runDelayed(fixture, new Location(world, 0, 1, 0), t -> seed(), 40));
            },
            40);
  }

  @SuppressWarnings("unchecked")
  private void seed() {
    try {
      y = world.getHighestBlockYAt(0, 0) + 1;
      for (int x = -3; x <= 12; x++)
        for (int z = -3; z <= 3; z++)
          for (int dy = -1; dy <= 3; dy++)
            world
                .getBlockAt(x, y + dy, z)
                .setType(
                    dy < 0 || (z == -1 || z == 1) && dy < 3 ? Material.STONE : Material.AIR, false);
      for (int x = 0; x <= 4; x++) {
        world
            .getBlockAt(x, y - 1, 0)
            .setBlockData(Bukkit.createBlockData("minecraft:farmland[moisture=7]"), false);
        world
            .getBlockAt(x, y, 0)
            .setBlockData(Bukkit.createBlockData("minecraft:carrots[age=0]"), false);
      }
      root =
          plugin
              .getDataFolder()
              .toPath()
              .resolve("CoreAI-lesson-fixture")
              .resolve(world.getUID().toString());
      var data = new Settlement.Data();
      data.world = world.getUID().toString();
      data.center = new Pos(0, y, 0);
      village = new Settlement(data);
      var settlements = CivilizationsPlugin.class.getDeclaredField("settlements");
      settlements.setAccessible(true);
      ((Map<String, Settlement>) settlements.get(plugin)).put(village.id(), village);
      queue =
          new InferenceQueue(
              new ModelBackend() {
                public boolean ready() {
                  return false;
                }

                public String status() {
                  return "offline lesson fixture";
                }

                public void close() {}

                public String complete(String s, String u) {
                  calls.incrementAndGet();
                  throw new AssertionError("Teacher not needed");
                }
              },
              4);
      solidClearanceContract();
      install();
      runWorker();
    } catch (Throwable error) {
      fail(error);
    }
  }

  private void solidClearanceContract() throws Exception {
    var rules = new dev.coreai.TerrainRuleBook(root.resolve("solid-contract"));
    rules.learn(
        new dev.coreai.TerrainRuleBook.Facts(
            "DIRT", "minecraft:dirt", true, false, true, false, false, false, false),
        "OBSTACLE",
        "measured solid",
        "host");
    Pos dirt = new Pos(1, 1, 0);
    var terrain =
        new dev.civilizations.world.Terrain() {
          public int height(int x, int z) {
            return 0;
          }

          public boolean available(int x, int z) {
            return true;
          }

          public String type(Pos p) {
            return p.equals(dirt) ? "DIRT" : p.y() < 1 ? "STONE" : "AIR";
          }

          public String blockData(Pos p) {
            return "minecraft:" + type(p).toLowerCase(java.util.Locale.ROOT);
          }
        };
    requireKind(
        dev.civilizations.navigation.NavigationMap.Kind.SOFT,
        dev.civilizations.world.NavigationTerrain.capture(
                terrain, new Pos(0, 1, 0), 2, Set.of(), rules, "worker")
            .cell(dirt)
            .kind());
    requireKind(
        dev.civilizations.navigation.NavigationMap.Kind.SOLID,
        dev.civilizations.world.NavigationTerrain.capture(
                terrain, new Pos(0, 1, 0), 2, Set.of(dirt), rules, "worker")
            .cell(dirt)
            .kind());
  }

  private static void requireKind(
      dev.civilizations.navigation.NavigationMap.Kind expected,
      dev.civilizations.navigation.NavigationMap.Kind actual) {
    if (expected != actual)
      throw new AssertionError(
          "Learned solid terrain: expected " + expected + " but was " + actual);
  }

  private void install() throws Exception {
    learning =
        new RecoveryExperiments(root, queue, () -> "no teacher", fixture.getLogger()::warning);
    var field = CivilizationsPlugin.class.getDeclaredField("experiments");
    field.setAccessible(true);
    field.set(plugin, learning);
    plugin.navigation().rules(learning.rules());
    var snapshots = CivilizationsPlugin.class.getDeclaredField("snapshots");
    snapshots.setAccessible(true);
    ((RegionSnapshots) snapshots.get(plugin)).rules(learning.rules());
  }

  @SuppressWarnings("unchecked")
  private void runWorker() throws Exception {
    if (phase == 1) {
      if (learning.rules().rule("new-worker", "CARROTS", "minecraft:carrots[age=0]") == null)
        throw new AssertionError("Carrot lesson missing after service reload");
      world
          .getBlockAt(1, y, 0)
          .setBlockData(Bukkit.createBlockData("minecraft:carrots[age=7]"), false);
    }
    Villager actor = world.spawn(new Location(world, .5, y + .05, .5), Villager.class);
    actor.setAdult();
    actor.getInventory().addItem(new ItemStack(Material.OAK_PLANKS, 1));
    village.enroll(actor.getUniqueId().toString(), 20);
    Pos target = new Pos(6 + phase * 3, y, 0);
    if (phase == 1) world.getBlockAt(6, y, 0).setType(Material.AIR, false);
    var job =
        new Job(
            Job.Kind.PLACE,
            "remembered-repair-" + phase,
            target,
            target.add(-1, 0, 0),
            "OAK_PLANKS",
            "AIR",
            null);
    village.addProject(job.project, List.of(job));
    var worker = new VillagerWorker(plugin, actor, village);
    var workers = CivilizationsPlugin.class.getDeclaredField("workers");
    workers.setAccessible(true);
    ((Map<String, VillagerWorker>) workers.get(plugin)).put(actor.getUniqueId().toString(), worker);
    worker.start();
    long started = System.currentTimeMillis();
    actor
        .getScheduler()
        .runAtFixedRate(
            fixture,
            task -> {
              try {
                if (job.complete) {
                  worker.stop();
                  task.cancel();
                  if (world.getBlockAt(target.x(), target.y(), target.z()).getType()
                          != Material.OAK_PLANKS
                      || InventoryOps.count(actor.getInventory(), Material.OAK_PLANKS) != 0
                      || calls.get() != 0)
                    throw new AssertionError("World/inventory/model evidence mismatch");
                  if (world.getBlockAt(0, y, 0).getType() != Material.CARROTS)
                    throw new AssertionError("Passable carrots were unnecessarily removed");
                  actor.remove();
                  learning.close();
                  if (phase++ == 0) {
                    fixture
                        .getLogger()
                        .info(
                            "LESSON FIRST PASS: ordinary worker learned carrot passability and"
                                + " repaired without inference; reloading stored lessons");
                    install();
                    runWorker();
                  } else {
                    var restored = new dev.coreai.TerrainRuleBook(root.resolve("rules"));
                    if (restored.rule("new", "CARROTS", "minecraft:carrots[age=7]") == null)
                      throw new AssertionError("Changed growth state not observed and retained");
                    fixture
                        .getLogger()
                        .info(
                            "LESSON MEMORY PASS: two actual repairs through carrots, retained age0"
                                + " rule after disk reload, newly observed age7 rule saved, carrots"
                                + " intact, exact plank consumption, zero model calls");
                    Bukkit.getGlobalRegionScheduler()
                        .runDelayed(fixture, t -> Bukkit.shutdown(), 20);
                  }
                } else if (System.currentTimeMillis() - started > 60000)
                  throw new AssertionError("Lesson worker deadline: " + worker.inspection());
              } catch (Throwable error) {
                task.cancel();
                worker.stop();
                fail(error);
              }
            },
            () -> {},
            10,
            10);
  }

  private void fail(Throwable error) {
    fixture.getLogger().severe("LESSON MEMORY FAIL: " + error);
    error.printStackTrace();
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> Bukkit.shutdown(), 20);
  }
}
