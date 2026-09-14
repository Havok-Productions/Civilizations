package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VillageConnectionsTest {
  private static final String WORLD = UUID.randomUUID().toString();
  @TempDir Path directory;

  private Settlement village(int x) {
    Settlement.Data d = new Settlement.Data();
    d.center = new Pos(x, 64, 0);
    d.radius = 12;
    d.world = WORLD;
    d.chest = new Pos(x, 64, 2);
    return new Settlement(d);
  }

  @org.junit.jupiter.api.Tag("settlements")
  @Test
  void proximityFollowsMembersAndSupportsTransitiveConnections() {
    Settlement a = village(0), b = village(40), c = village(80);
    assertTrue(VillageConnections.connected(a, b, 48, 16));
    assertFalse(VillageConnections.connected(a, c, 48, 16));
    Settlement combined = VillageConnections.combine(List.of(a, b));
    assertTrue(VillageConnections.connected(combined, c, 48, 16));
    combined.enroll("worker", 20);
    combined.position("worker", new Pos(120, 64, 0));
    assertTrue(VillageConnections.connected(combined, village(160), 48, 16));
    assertFalse(VillageConnections.near(new Pos(0, 64, 0), new Pos(0, 100, 0), 48, 16));
    Settlement other = CoreTest.village();
    assertFalse(VillageConnections.connected(a, other, 48, 16));
  }

  @org.junit.jupiter.api.Tag("settlements")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void combiningKeepsProjectsCommitmentsBedsProtectionAndActualJobIds() {
    Settlement a = village(0), b = village(40);
    for (Settlement v : List.of(a, b)) {
      String worker = v.id();
      v.enroll(worker, 20);
      Job j =
          new Job(
              Job.Kind.PLACE, "lights", v.center(), v.center().add(1, 0, 0), "TORCH", "AIR", null);
      v.addProject("lights", List.of(j));
      v.taskProject(worker, "lights");
      v.knowledge().need("COAL", "lights", System.currentTimeMillis());
      v.knowledge().progress(worker, "lights", "gather", "no coal", "", System.currentTimeMillis());
      v.beds(List.of(v.center().add(2, 0, 0)));
      v.playerPlaced(v.center().add(3, 0, 0));
    }
    Settlement merged = VillageConnections.combine(List.of(a, b));
    assertEquals(2, merged.population());
    assertEquals(2, merged.chests().size());
    assertEquals(2, merged.jobs().stream().map(j -> j.project).distinct().count());
    assertEquals(2, merged.beds().size());
    assertEquals(2, merged.supplyNeeds(System.currentTimeMillis()).size());
    for (String worker : merged.members()) assertNotNull(merged.knowledge().worker(worker));
    for (Settlement old : List.of(a, b)) {
      Job prior = old.jobs().getFirst();
      assertTrue(merged.jobs().stream().anyMatch(j -> j.id.equals(prior.id)));
      assertTrue(merged.playerProtected(old.center().add(3, 0, 0)));
      assertFalse(merged.mayShareSurplus(old.id()));
      assertTrue(merged.claim(prior.id, old.id(), 100));
      merged.done(prior.id, old.id());
      assertTrue(merged.mayShareSurplus(old.id()));
    }
  }

  @org.junit.jupiter.api.Tag("navigation")
  @org.junit.jupiter.api.Tag("storage")
  @org.junit.jupiter.api.Tag("settlements")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void multiChestStockDoesNotOverwriteOtherStoresAndRoutesToUsefulStore() {
    Settlement merged = VillageConnections.combine(List.of(village(0), village(40)));
    Pos first = merged.chests().get(0), second = merged.chests().get(1);
    merged.stock(first, Map.of("COAL", 4), 100);
    merged.stock(second, Map.of("OAK_LOG", 10), 110);
    assertEquals(Map.of("COAL", 4, "OAK_LOG", 10), merged.stock());
    assertEquals(first, merged.supplyChest(second, s -> s.getOrDefault("COAL", 0) > 0, 120));
    merged.stock(second, Map.of("BIRCH_LOG", 3), 120);
    assertEquals(4, merged.stock().get("COAL"));
    merged.knowledge().block("storage-full:" + second.key(), "full", 120, 1000);
    assertEquals(first, merged.depositChest(second, 121));
    merged.removeChest(first);
    assertFalse(merged.stock().containsKey("COAL"));
  }

  @org.junit.jupiter.api.Tag("settlements")
  @org.junit.jupiter.api.Tag("diagnostics")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void restartSuppressesAbsorbedFilesWithoutDeletingTheirOriginalState() throws Exception {
    StateStore store = new StateStore(directory);
    Settlement a = village(0), b = village(40), c = village(80);
    store.save(List.of(a.snapshot(), b.snapshot(), c.snapshot()));
    Settlement merged = VillageConnections.combine(List.of(a, b));
    store.save(List.of(merged.snapshot(), c.snapshot()));
    assertEquals(
        2,
        store
            .load(
                fail -> {
                  throw new AssertionError(fail);
                })
            .size());
    merged = VillageConnections.combine(List.of(merged, c));
    store.save(List.of(merged.snapshot()));
    List<Settlement> loaded =
        store.load(
            fail -> {
              throw new AssertionError(fail);
            });
    assertEquals(1, loaded.size());
    assertEquals(3, loaded.getFirst().chests().size());
    assertEquals(2, loaded.getFirst().absorbedIds().size());
    assertEquals(
        3, java.nio.file.Files.list(directory).filter(p -> p.toString().endsWith(".json")).count());
  }

  @org.junit.jupiter.api.Tag("settlements")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void migrationWaitsForInFlightActionAndRejectsNewWorkOnRetiredVillage() throws Exception {
    VillageConnections connections = new VillageConnections();
    Settlement village = village(0);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<?> action =
          executor.submit(
              () ->
                  connections.read(
                      () -> {
                        entered.countDown();
                        try {
                          release.await();
                        } catch (InterruptedException e) {
                          throw new RuntimeException(e);
                        }
                      }));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      Future<?> merge =
          executor.submit(
              () ->
                  connections.change(
                      () -> {
                        village.retire();
                        return null;
                      }));
      assertThrows(TimeoutException.class, () -> merge.get(50, TimeUnit.MILLISECONDS));
      release.countDown();
      action.get(2, TimeUnit.SECONDS);
      merge.get(2, TimeUnit.SECONDS);
      assertTrue(village.paused());
      assertFalse(village.addProject("stale", List.of(CoreTest.job(new Pos(1, 64, 1)))));
    } finally {
      release.countDown();
    }
  }
}
