package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import java.util.*;
import org.junit.jupiter.api.*;

class TaskProbeTest {
  @Test
  @Tag("tasks")
  @Tag("diagnostics")
  @Tag("interaction")
  void nearestWorkerHasNoSixteenBlockCutoffAndUsesThreeDimensionsWithinOneWorld() {
    UUID world = UUID.randomUUID();
    var origin = new WorkerPosition("player", world, -4163.02, 71.37, -1345);
    var nearest = new WorkerPosition("near", world, -4140, 71, -1345);
    var high = new WorkerPosition("above", world, -4163, 120, -1345);
    var otherWorld =
        new WorkerPosition("wrong-world", UUID.randomUUID(), origin.x(), origin.y(), origin.z());
    assertEquals(
        nearest, WorkerPosition.nearest(origin, List.of(high, otherWorld, nearest)).orElseThrow());
    assertTrue(WorkerPosition.nearest(origin, List.of(otherWorld)).isEmpty());
    assertTrue(WorkerPosition.nearest(origin, List.of()).isEmpty());
    assertEquals(high, WorkerPosition.nearest(origin, List.of(high)).orElseThrow());
  }

  @Test
  @Tag("tasks")
  @Tag("diagnostics")
  @Tag("interaction")
  void observedMovementAndItemsCannotPassWithoutTheAssignedExecutorReceipt() {
    Job job = CoreTest.job(new Pos(8, 65, 0));
    var probe = new TaskProbe(job, 100, 1000, new Pos(0, 65, 0), Map.of("OAK_LOG", 1));
    probe.observe(
        200, new Pos(4, 65, 0), Map.of("OAK_PLANKS", 3), "building", "OAK_PLANKS", Map.of());
    probe.verified("different-job", true, 210);
    assertEquals(TaskProbe.Result.RUNNING, probe.result());
    assertEquals(4.0, probe.evidence(210).get("maximum_displacement_blocks"));
    var observation = (Map<?, ?>) probe.evidence(210).get("observation");
    assertEquals(Map.of("OAK_LOG", -1, "OAK_PLANKS", 3), observation.get("inventory_delta"));
    probe.verified(job.id, true, 220);
    assertEquals(TaskProbe.Result.PASS, probe.result());
    probe.failure("late unrelated failure");
    assertEquals(0, probe.evidence(230).get("failures"));
  }

  @Test
  @Tag("tasks")
  @Tag("diagnostics")
  @Tag("interaction")
  void noOpTimeoutAndInterruptionNeverCountAsSuccessfulWork() {
    Job job = CoreTest.job(new Pos(8, 65, 0));
    var noOp = new TaskProbe(job, 100, 1000, new Pos(0, 65, 0), Map.of());
    noOp.verified(job.id, false, 200);
    assertEquals(TaskProbe.Result.ALREADY_SATISFIED, noOp.result());
    var failed = new TaskProbe(job, 100, 1000, new Pos(0, 65, 0), Map.of());
    failed.failure("Work blocked by protection");
    failed.observe(1100, new Pos(0, 65, 0), Map.of(), "rest", "DIRT", Map.of());
    failed.verified(job.id, true, 1101);
    assertEquals(TaskProbe.Result.TIMEOUT, failed.result());
    assertEquals("Work blocked by protection", failed.evidence(1102).get("last_failure"));
    var interrupted = new TaskProbe(job, 100, 1000, new Pos(0, 65, 0), Map.of());
    interrupted.finish(TaskProbe.Result.INTERRUPTED, "unloaded", 200);
    assertEquals(TaskProbe.Result.INTERRUPTED, interrupted.result());
    assertThrows(
        IllegalArgumentException.class,
        () -> new TaskProbe(job, 100, 0, new Pos(0, 0, 0), Map.of()));
  }
}
