package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.ai.*;
import dev.civilizations.core.*;
import dev.civilizations.world.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CoreTest {
  @TempDir Path tmp;

  static Settlement village() {
    Settlement.Data d = new Settlement.Data();
    d.world = UUID.randomUUID().toString();
    d.center = new Pos(0, 65, 0);
    d.radius = 12;
    return new Settlement(d);
  }

  static Job job(Pos p) {
    return new Job(Job.Kind.PLACE, "wall-12", p, p.add(1, 0, 0), "COBBLESTONE", "AIR", null);
  }

  @org.junit.jupiter.api.Tag("settlements")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void exactlyOneWorkerCanClaimAndExpiredLeaseCanBeRecovered() throws Exception {
    Settlement v = village();
    Job j = job(new Pos(1, 65, 1));
    v.addProject(j.project, List.of(j));
    AtomicInteger claimed = new AtomicInteger();
    try (var pool = Executors.newFixedThreadPool(8)) {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 30; i++) {
        String id = "worker-" + i;
        futures.add(
            pool.submit(
                () -> {
                  if (v.claim(j.id, id, 100)) claimed.incrementAndGet();
                }));
      }
      for (var f : futures) f.get();
    }
    assertEquals(1, claimed.get());
    assertTrue(v.claim(j.id, "replacement", 61_000));
    v.done(j.id, "wrong-owner");
    assertFalse(v.jobs().getFirst().complete);
    v.done(j.id, "replacement");
    assertTrue(v.jobs().getFirst().complete);
  }

  @org.junit.jupiter.api.Tag("tasks")
  @Test
  void miningRequiresPreviousStepAndDoesNotCompleteOnFailure() {
    Settlement v = village();
    Job a = new Job(Job.Kind.MINE, "mine", new Pos(1, 64, 1), new Pos(0, 65, 1), "", "STONE", null),
        b = new Job(Job.Kind.MINE, "mine", new Pos(2, 63, 1), new Pos(1, 64, 1), "", "STONE", null);
    v.addProject("mine", List.of(a, b));
    assertFalse(v.claim(b.id, "b", 0));
    assertTrue(v.claim(a.id, "a", 0));
    v.failed(a.id, "a", 0, "water");
    assertFalse(v.jobs().getFirst().complete);
    assertFalse(v.claim(a.id, "a", 100));
    assertTrue(v.claim(a.id, "a", 20_000));
    v.done(a.id, "a");
    assertTrue(v.claim(b.id, "b", 20_000));
  }

  @org.junit.jupiter.api.Tag("settlements")
  @org.junit.jupiter.api.Tag("diagnostics")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void preservesIdentityMemoriesAndConstructionAcrossRestart() throws Exception {
    Settlement v = village();
    v.enroll("a", 5);
    v.remember("a", "Delivered 8 stone", true);
    Job j = job(new Pos(1, 65, 1));
    v.addProject(j.project, List.of(j));
    v.claim(j.id, "a", 0);
    v.done(j.id, "a");
    v.playerPlaced(new Pos(3, 65, 3));
    StateStore store = new StateStore(tmp);
    store.save(List.of(v.snapshot()));
    Settlement restored = store.load(s -> fail(s)).getFirst();
    assertEquals(v.id(), restored.id());
    assertTrue(restored.allComplete("wall-12"));
    assertEquals(2, restored.memories("a").size());
    assertTrue(restored.playerProtected(new Pos(3, 65, 3)));
    restored.damaged(j.id);
    assertFalse(restored.allComplete("wall-12"));
    assertTrue(restored.claim(j.id, "a", 0));
  }

  @org.junit.jupiter.api.Tag("settlements")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void agentsHaveSeparateMemoriesAndSharedPopulationLimit() {
    Settlement v = village();
    assertTrue(v.enroll("one", 2));
    assertTrue(v.enroll("two", 2));
    assertFalse(v.enroll("three", 2));
    v.remember("one", "Blocked at river", false);
    assertEquals(1, v.memories("one").size());
    assertTrue(v.memories("two").isEmpty());
  }

  @org.junit.jupiter.api.Tag("settlements")
  @org.junit.jupiter.api.Tag("diagnostics")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void corruptSaveDoesNotSilentlyReplaceExistingSettlements() throws Exception {
    Files.writeString(tmp.resolve("broken.json"), "{broken");
    assertThrows(java.io.IOException.class, () -> new StateStore(tmp).load(message -> {}));
  }

  @org.junit.jupiter.api.Tag("tasks")
  @Test
  void unavailableAndOverlappingPlansAreNotAccepted() {
    Settlement v = village();
    assertFalse(v.addProject("empty", List.of()));
    Job a = job(new Pos(1, 65, 1));
    assertTrue(v.addProject("wall", List.of(a)));
    assertFalse(v.addProject("house", List.of(job(a.target))));
  }

  @org.junit.jupiter.api.Tag("tasks")
  @Test
  void offersRespectReservationsHouseOrderAndProtectedStandingFloors() {
    Settlement v = village();
    Job wall = job(new Pos(12, 65, 0));
    Job house =
        new Job(
            Job.Kind.PLACE,
            "house",
            new Pos(4, 65, 4),
            new Pos(3, 65, 4),
            "OAK_PLANKS",
            "AIR",
            null);
    v.addProject(wall.project, List.of(wall));
    v.addProject("house", List.of(house));
    assertFalse(v.available(house.id, "a", 0));
    assertTrue(v.claim(wall.id, "a", 0));
    assertFalse(v.available(wall.id, "b", 0));
    v.done(wall.id, "a");
    assertTrue(v.available(house.id, "b", 0));
    assertTrue(v.gatherProtected(house.stand.add(0, -1, 0)));
  }

  @org.junit.jupiter.api.Tag("inference")
  @Test
  void rejectsInvalidOrInventedModelActions() {
    assertThrows(Exception.class, () -> Decision.parse("{\"action\":\"command\"}", Set.of()));
    assertThrows(
        Exception.class,
        () -> Decision.parse("{\"action\":\"work\",\"job_id\":\"invented\"}", Set.of("real")));
    assertThrows(
        Exception.class,
        () -> Decision.parse("{\"action\":\"gather\",\"material\":\"DIAMOND\"}", Set.of()));
    assertEquals(
        "work",
        Decision.parse("{\"action\":\"work\",\"job_id\":\"real\"}", Set.of("real")).action());
  }

  @org.junit.jupiter.api.Tag("inference")
  @Test
  void inferenceIsBoundedDeduplicatedAndRecoversAfterCompletion() throws Exception {
    CountDownLatch entered = new CountDownLatch(1),
        release = new CountDownLatch(1),
        done = new CountDownLatch(2);
    ModelBackend model =
        new ModelBackend() {
          public boolean ready() {
            return true;
          }

          public String status() {
            return "test";
          }

          public String complete(String a, String b) throws Exception {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "{\"action\":\"rest\"}";
          }

          public void close() {}
        };
    try (InferenceQueue q = new InferenceQueue(model, 1)) {
      assertTrue(q.request("a", "", Set.of(), d -> done.countDown()));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertFalse(q.request("a", "", Set.of(), d -> {}));
      assertTrue(q.request("b", "", Set.of(), d -> done.countDown()));
      assertFalse(q.request("c", "", Set.of(), d -> {}));
      release.countDown();
      assertTrue(done.await(2, TimeUnit.SECONDS));
    }
  }

  @org.junit.jupiter.api.Tag("runtime")
  @Test
  void rejectsRemoteEndpointsAndArchiveTraversal() throws Exception {
    assertEquals(
        "http://127.0.0.1:8642/v1", LocalRuntime.localEndpoint("http://127.0.0.1:8642/v1/"));
    assertThrows(Exception.class, () -> LocalRuntime.localEndpoint("https://example.com/v1"));
    assertThrows(Exception.class, () -> LocalRuntime.safeEntry(tmp, "../outside.exe"));
    assertThrows(Exception.class, () -> LocalRuntime.safeEntry(tmp, "..\\outside.exe"));
    assertTrue(LocalRuntime.safeEntry(tmp, "bin/server.exe").startsWith(tmp));
  }

  static class Flat implements Terrain {
    Map<Pos, String> overrides = new HashMap<>();

    public int height(int x, int z) {
      return 64;
    }

    public String type(Pos p) {
      return overrides.getOrDefault(p, p.y() > 64 ? "AIR" : p.y() == 64 ? "GRASS_BLOCK" : "STONE");
    }

    public boolean available(int x, int z) {
      return Math.abs(x) <= 64 && Math.abs(z) <= 64;
    }
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void wallHasOneClosedGateAndDoesNotCrossWaterOrBuildings() {
    Planner p = new Planner();
    Flat terrain = new Flat();
    List<Job> wall = p.wall(terrain, new Pos(0, 65, 0), 12, 3);
    assertFalse(wall.isEmpty());
    assertEquals(1, wall.stream().filter(j -> j.material.equals("OAK_FENCE_GATE")).count());
    assertEquals(wall.size(), wall.stream().map(j -> j.target).distinct().count());
    terrain.overrides.put(new Pos(12, 64, 0), "WATER");
    assertTrue(p.wall(terrain, new Pos(0, 65, 0), 12, 3).isEmpty());
    terrain.overrides.clear();
    terrain.overrides.put(new Pos(-12, 65, 0), "OAK_PLANKS");
    assertTrue(p.wall(terrain, new Pos(0, 65, 0), 12, 3).isEmpty());
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void mineFormsDescendingWalkableStaircaseAndRejectsFluidEnvelope() {
    Planner p = new Planner();
    Flat t = new Flat();
    List<Job> mine = p.mine(t, new Pos(0, 65, 0), 12, 6, 6);
    assertFalse(mine.isEmpty());
    assertTrue(mine.stream().allMatch(j -> j.target.horizontal2(new Pos(0, 65, 0)) >= 256));
    for (Job j : mine) {
      assertTrue(j.target.distance2(j.stand) <= 21);
      assertTrue(Math.abs(j.target.y() - j.stand.y()) <= 2);
    }
    Terrain wet =
        new Flat() {
          @Override
          public String type(Pos pos) {
            return pos.y() == 63 ? "WATER" : super.type(pos);
          }
        };
    assertTrue(p.mine(wet, new Pos(0, 65, 0), 12, 6, 6).isEmpty());
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void houseHasRoofBedsAndUniquePositions() {
    List<Job> house = new Planner().house(new Flat(), new Pos(0, 65, 0), 12);
    assertFalse(house.isEmpty());
    assertEquals(2, house.stream().filter(j -> j.material.equals("WHITE_BED")).count());
    assertEquals(house.size(), house.stream().map(j -> j.target).distinct().count());
    assertEquals(25, house.stream().filter(j -> j.target.y() == 69).count());
  }
}
