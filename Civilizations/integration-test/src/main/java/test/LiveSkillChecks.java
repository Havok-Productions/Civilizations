package test;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.ai.*;
import dev.civilizations.core.*;
import dev.civilizations.learning.*;
import dev.civilizations.navigation.*;
import dev.civilizations.world.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** A deterministic proposal exercises real blocks, inventory, movement and outcome persistence. */
final class LiveSkillChecks {
  private final JavaPlugin fixture;

  LiveSkillChecks(JavaPlugin fixture) {
    this.fixture = fixture;
  }

  void start() {
    Bukkit.getGlobalRegionScheduler()
        .runDelayed(
            fixture,
            t -> {
              World world = Bukkit.getWorlds().getFirst();
              world.setTime(1000);
              List<CompletableFuture<Void>> chunks = new ArrayList<>();
              for (int x = -3; x <= 3; x++)
                for (int z = -3; z <= 3; z++) {
                  int cx = x, cz = z;
                  CompletableFuture<Void> ready = new CompletableFuture<>();
                  chunks.add(ready);
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
                          e -> {
                            ready.completeExceptionally(e);
                            return null;
                          });
                }
              CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new))
                  .orTimeout(30, TimeUnit.SECONDS)
                  .whenComplete(
                      (v, e) -> {
                        if (e != null) fail(e);
                        else
                          Bukkit.getRegionScheduler()
                              .runDelayed(
                                  fixture, new Location(world, 0, 1, 0), q -> seed(world), 60);
                      });
            },
            60);
  }

  @SuppressWarnings("unchecked")
  private void seed(World world) {
    try {
      CivilizationsPlugin plugin =
          (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
      int y = world.getHighestBlockYAt(0, 0) + 4;
      for (int x = -8; x <= 12; x++)
        for (int z = -6; z <= 6; z++)
          for (int dy = -1; dy <= 3; dy++)
            world
                .getBlockAt(x, y + dy, z)
                .setType(dy == -1 && (x < 3 || x > 4) ? Material.STONE : Material.AIR, false);
      world.getBlockAt(0, y, 2).setType(Material.LEAF_LITTER, false);
      Settlement.Data data = new Settlement.Data();
      data.world = world.getUID().toString();
      data.center = new Pos(2, y, 0);
      Settlement village = new Settlement(data);
      var villages = CivilizationsPlugin.class.getDeclaredField("settlements");
      villages.setAccessible(true);
      ((Map<String, Settlement>) villages.get(plugin)).put(village.id(), village);
      Villager actor = world.spawn(new Location(world, 2.5, y, .5), Villager.class);
      actor.setAdult();
      village.enroll(actor.getUniqueId().toString(), 20);
      actor.getInventory().addItem(new ItemStack(Material.DIRT, 2));
      Terrain terrain =
          new Terrain() {
            public int height(int x, int z) {
              return y - 1;
            }

            public boolean available(int x, int z) {
              return true;
            }

            public String type(Pos p) {
              return world.getBlockAt(p.x(), p.y(), p.z()).getType().name();
            }

            public String blockData(Pos p) {
              return world.getBlockAt(p.x(), p.y(), p.z()).getBlockData().getAsString();
            }
          };
      NavigationMap map = NavigationTerrain.capture(terrain, data.center, 8, Set.of());
      if (map.cell(new Pos(0, y, 2)).kind() != NavigationMap.Kind.AIR)
        throw new AssertionError("Leaf litter remains hazardous");
      String proposal =
          """
          {"explanation":"Fixture: bridge the two-block dry gap with actual dirt, then walk across.","steps":[
          {"op":"PLACE_SUPPORT","x":1,"y":-1,"z":0,"material":"DIRT"},
          {"op":"PLACE_SUPPORT","x":2,"y":-1,"z":0,"material":"DIRT"},
          {"op":"WALK","x":4,"y":0,"z":0,"material":""},
          {"op":"VERIFY","x":0,"y":0,"z":0,"material":""}]}
          """;
      ModelBackend fake =
          new ModelBackend() {
            public boolean ready() {
              return true;
            }

            public String status() {
              return "fixture";
            }

            public void close() {}

            public String complete(String system, String report) {
              if (!report.contains("goal_relative") || !report.contains("DIRT"))
                throw new AssertionError("Missing observation");
              return proposal;
            }
          };
      InferenceQueue queue = new InferenceQueue(fake, 4);
      Path evidence = plugin.getDataFolder().toPath().resolve("CoreAI-live-fixture");
      RecoveryExperiments service =
          new RecoveryExperiments(
              evidence,
              queue,
              () -> "deterministic fixture; not a real model",
              fixture.getLogger()::warning);
      var field = CivilizationsPlugin.class.getDeclaredField("experiments");
      field.setAccessible(true);
      field.set(plugin, service);
      WorkMovementControl control =
          new WorkMovementControl(
              actor,
              message -> {
                throw new AssertionError(message);
              });
      control.working(true);
      java.util.concurrent.atomic.AtomicReference<Boolean> result =
          new java.util.concurrent.atomic.AtomicReference<>();
      WorkerSkillTrial skill =
          new WorkerSkillTrial(
              plugin,
              actor,
              village,
              (success, reason) -> {
                fixture.getLogger().info("SKILL RESULT: " + reason);
                result.set(success);
              });
      long started = System.currentTimeMillis();
      if (!skill.start(map, new Pos(6, y, 0), 1, "fixture_disconnected_floor", started))
        throw new AssertionError("No pilot admitted");
      actor
          .getScheduler()
          .runAtFixedRate(
              fixture,
              t -> {
                try {
                  control.working(true);
                  skill.tick(System.currentTimeMillis());
                  if (result.get() != null) {
                    if (!result.get()
                        || actor.getLocation().getX() < 5.5
                        || world.getBlockAt(3, y - 1, 0).getType() != Material.DIRT
                        || world.getBlockAt(4, y - 1, 0).getType() != Material.DIRT
                        || InventoryOps.count(actor.getInventory(), Material.DIRT) != 0)
                      throw new AssertionError(
                          "Actual movement, support blocks or ingredient conservation failed");
                    t.cancel();
                    control.working(false);
                    Bukkit.getAsyncScheduler()
                        .runDelayed(
                            fixture,
                            q -> {
                              try {
                                String saved =
                                    Files.readString(evidence.resolve("skills/skills.json"));
                                if (!saved.contains("verified_once"))
                                  throw new AssertionError("Observed success not persisted");
                                fixture
                                    .getLogger()
                                    .info(
                                        "LIVE SKILL PASS: actual two-block support placement, two"
                                            + " dirt consumed, villager crossed gap, original-goal"
                                            + " arrival and learned outcome persisted; leaf litter"
                                            + " walkable. Proposal was deterministic fixture, not"
                                            + " model intelligence.");
                                service.close();
                                queue.close();
                                Bukkit.getGlobalRegionScheduler()
                                    .run(fixture, k -> Bukkit.shutdown());
                              } catch (Throwable e) {
                                fail(e);
                              }
                            },
                            1,
                            TimeUnit.SECONDS);
                  } else if (System.currentTimeMillis() - started > 30000)
                    throw new AssertionError("Physical skill timeout at " + actor.getLocation());
                } catch (Throwable e) {
                  t.cancel();
                  skill.cancel("fixture_failed");
                  control.working(false);
                  service.close();
                  queue.close();
                  fail(e);
                }
              },
              () -> {},
              1,
              5);
    } catch (Throwable e) {
      fail(e);
    }
  }

  private void fail(Throwable error) {
    fixture.getLogger().severe("LIVE SKILL FAIL: " + error);
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture, t -> Bukkit.shutdown(), 20);
  }
}
