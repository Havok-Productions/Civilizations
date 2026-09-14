package test;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.Settlement;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Villager;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable server assertions. Reads registration state; never calls create/attach/discover. */
public final class DiscoveryChecks {
  private final JavaPlugin fixture;
  private CivilizationsPlugin plugin;
  private Map<String, Settlement> villages;
  private Map<String, ?> workers;
  private World world;
  private Path marker;

  public DiscoveryChecks(JavaPlugin fixture) {
    this.fixture = fixture;
  }

  @SuppressWarnings("unchecked")
  public void start() {
    try {
      plugin = (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
      var settlements = CivilizationsPlugin.class.getDeclaredField("settlements");
      settlements.setAccessible(true);
      villages = (Map<String, Settlement>) settlements.get(plugin);
      var active = CivilizationsPlugin.class.getDeclaredField("workers");
      active.setAccessible(true);
      workers = (Map<String, ?>) active.get(plugin);
      marker = fixture.getDataFolder().toPath().resolve("discovery-test-id.txt");
      Bukkit.getGlobalRegionScheduler().runDelayed(fixture, task -> prepare(), 60);
    } catch (Exception e) {
      fail(e.toString());
    }
  }

  private void prepare() {
    if (!Bukkit.getOnlinePlayers().isEmpty()) {
      fail("Test requires zero players");
      return;
    }
    world = Bukkit.getWorlds().getFirst();
    world.setTime(1000);
    List<CompletableFuture<Void>> pending = new ArrayList<>();
    for (int x = -3; x <= 3; x++)
      for (int z = -3; z <= 3; z++) {
        int cx = x, cz = z;
        CompletableFuture<Void> f = new CompletableFuture<>();
        pending.add(f);
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
                              f.complete(null);
                            }))
            .exceptionally(
                error -> {
                  f.completeExceptionally(error);
                  return null;
                });
      }
    CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
        .thenRun(
            () ->
                Bukkit.getRegionScheduler()
                    .execute(fixture, new Location(world, 0, 1, 0), this::seed));
  }

  private void seed() {
    try {
      if (Files.exists(marker)) {
        String id = Files.readString(marker).trim();
        Bukkit.getRegionScheduler()
            .runDelayed(
                fixture,
                new Location(world, 0, 1, 0),
                t -> {
                  Settlement v =
                      villages.values().stream()
                          .filter(s -> s.members().contains(id))
                          .findFirst()
                          .orElse(null);
                  if (v == null
                      || v.population() != 5
                      || v.members().stream().filter(workers::containsKey).count() != 5) {
                    fail("Saved population did not reconnect after restart");
                    return;
                  }
                  pass(
                      "Five saved workers reattached in loaded chunks with zero players; UUID="
                          + v.id());
                },
                300);
        return;
      }
      Villager first = world.spawn(new Location(world, 0, 1, 0), Villager.class);
      first.setAdult();
      first.setPersistent(true);
      String firstId = first.getUniqueId().toString();
      Files.createDirectories(marker.getParent());
      Files.writeString(marker, firstId);
      Bukkit.getRegionScheduler()
          .runDelayed(
              fixture,
              new Location(world, 0, 1, 0),
              task -> {
                Settlement v =
                    villages.values().stream()
                        .filter(s -> s.members().contains(firstId))
                        .findFirst()
                        .orElse(null);
                if (v == null || v.population() != 1 || !workers.containsKey(firstId)) {
                  fail("Single adult spawn was not enrolled automatically");
                  return;
                }
                pass("One adult spawned with zero players started a settlement without a command");
                // Suppress load/spawn event shortcuts to prove the periodic loaded-chunk sweep
                // works.
                HandlerList.unregisterAll((org.bukkit.plugin.Plugin) plugin);
                for (int i = 1; i <= 5; i++) {
                  Villager adult = world.spawn(new Location(world, i, 1, 0), Villager.class);
                  adult.setAdult();
                  adult.setPersistent(true);
                }
                Villager baby = world.spawn(new Location(world, 1, 1, 1), Villager.class);
                baby.setBaby();
                baby.setAgeLock(true);
                String babyId = baby.getUniqueId().toString();
                Bukkit.getRegionScheduler()
                    .runDelayed(
                        fixture,
                        new Location(world, 0, 1, 0),
                        check -> {
                          if (v.population() != 5
                              || v.members().contains(babyId)
                              || v.members().stream().filter(workers::containsKey).count() != 5) {
                            fail("Loaded-chunk sweep / adult filter / population cap failed");
                            return;
                          }
                          pass(
                              "Periodic sweep found existing adults with event shortcuts disabled;"
                                  + " five-worker cap and baby exclusion verified; players="
                                  + Bukkit.getOnlinePlayers().size());
                        },
                        320);
              },
              80);
    } catch (Exception e) {
      fail(e.toString());
    }
  }

  private void pass(String text) {
    fixture.getLogger().info("DISCOVERY PASS: " + text);
  }

  private void fail(String text) {
    fixture.getLogger().severe("DISCOVERY FAILED: " + text);
  }
}
