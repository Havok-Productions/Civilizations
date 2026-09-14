package dev.civilizations.learning;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import dev.coreai.agent.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class VillagerMindTest {
  @Test
  @Tag("coreai")
  @Tag("tasks")
  @Tag("interaction")
  void claimedMinecraftJobKeepsItsObservationAndOnlyItsOwnVerifiedReceiptCompletesIt() {
    var data = new Settlement.Data();
    data.world = UUID.randomUUID().toString();
    data.center = new Pos(0, 64, 0);
    var village = new Settlement(data);
    village.enroll("worker", 20);
    var job =
        new Job(
            Job.Kind.PLACE,
            "wall",
            new Pos(2, 64, 0),
            new Pos(1, 64, 0),
            "COBBLESTONE",
            "AIR",
            null);
    List<AgentSession.Experience> recorded = new ArrayList<>();
    AtomicInteger starts = new AtomicInteger();
    try (var mind = new VillagerMind("worker", village, recorded::add, e -> fail(e))) {
      Map<String, Integer> carried = new HashMap<>(Map.of("COBBLESTONE", 2));
      mind.begin(job, data.center, carried, 100, starts::incrementAndGet);
      carried.clear();
      job.target = new Pos(99, 64, 0);
      assertEquals(1, starts.get());
      assertTrue(recorded.isEmpty());
      mind.succeeded("some-other-job", Map.of("block", "COBBLESTONE"));
      assertTrue(recorded.isEmpty());
      mind.succeeded(
          job.id, Map.of("block", "COBBLESTONE", "inventory_after", Map.of("COBBLESTONE", 1)));
      mind.succeeded(job.id, Map.of("block", "COBBLESTONE"));
      mind.cancel("normal reset");
      assertEquals(1, recorded.size());
      var entry = recorded.getFirst();
      assertEquals(
          2,
          entry
              .attempt()
              .action()
              .arguments()
              .object()
              .getAsJsonObject("target")
              .get("x")
              .getAsInt());
      assertEquals(
          2,
          entry
              .attempt()
              .observation()
              .facts()
              .object()
              .getAsJsonObject("inventory")
              .get("COBBLESTONE")
              .getAsInt());
      assertEquals("minecraft:place", entry.attempt().action().capability());
      assertEquals(Outcome.Status.SUCCEEDED, entry.outcome().status());
      assertFalse(mind.report().isEmpty());
      mind.begin(job, data.center, Map.of(), 200, starts::incrementAndGet);
      mind.failed("no route", Map.of("observed", "wall"));
      assertEquals(Outcome.Status.FAILED, recorded.getLast().outcome().status());
      mind.begin(job, data.center, Map.of(), 300, starts::incrementAndGet);
    }
    assertEquals(Outcome.Status.CANCELLED, recorded.getLast().outcome().status());
    assertEquals(3, recorded.size());
  }
}
