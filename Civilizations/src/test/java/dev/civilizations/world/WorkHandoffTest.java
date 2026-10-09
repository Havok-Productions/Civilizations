package dev.civilizations.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import java.util.*;
import org.junit.jupiter.api.*;

class WorkHandoffTest {
  @Test
  @Tag("tasks")
  @Tag("navigation")
  void dangerSleepAndRecoveryEachExcludeLowerPriorityActions() {
    var controller = new WorkerActivities();
    for (var exclusive :
        List.of(
            WorkerActivities.Owner.EMERGENCY,
            WorkerActivities.Owner.SLEEP,
            WorkerActivities.Owner.RECOVERY)) {
      var tried = new ArrayList<WorkerActivities.Owner>();
      controller.tick(
          owner -> {
            tried.add(owner);
            return owner == exclusive || owner == WorkerActivities.Owner.WORK;
          });
      assertEquals(exclusive, controller.owner());
      assertFalse(tried.contains(WorkerActivities.Owner.DELIVERY));
      assertFalse(tried.contains(WorkerActivities.Owner.WORK));
    }
    controller.tick(owner -> owner == WorkerActivities.Owner.WORK);
    assertEquals(WorkerActivities.Owner.WORK, controller.owner());
    assertThrows(
        IllegalStateException.class,
        () ->
            controller.tick(
                owner -> {
                  controller.tick(nested -> true);
                  return true;
                }));
    controller.tick(owner -> owner == WorkerActivities.Owner.WORK);
  }

  @Test
  @Tag("tasks")
  @Tag("crafting")
  @Tag("diagnostics")
  void prerequisiteWaitResumesOnEvidenceChangeOrRetryWithoutReplacingParent() {
    var wait = new PrerequisiteWait();
    var target = new Pos(18, 64, 4);
    var unsupported =
        new WorkIssue(
            WorkIssue.Action.GATHER,
            target,
            "STRING",
            "Harvest capability unavailable",
            WorkIssue.Retry.WAIT_FOR_CHANGE);
    wait.deferUntilChanged("wall-step", "no string");
    wait.clear(); // Sleep/reset may release the active job; its deferred checkpoint remains useful.
    assertFalse(wait.eligible("wall-step", "no string"));
    assertTrue(wait.eligible("independent-step", "no string"));
    assertTrue(wait.eligible("wall-step", "string delivered"));
    wait.defer("wall-step", unsupported, "no supplies", 5000);
    assertTrue(wait.waiting("wall-step", "no supplies", 100_000));
    assertEquals(target, wait.issue().target());
    assertFalse(wait.waiting("wall-step", "string delivered", 100_001));
    assertNull(wait.issue());
    var temporary =
        new WorkIssue(
            WorkIssue.Action.SUPPLY,
            target,
            "OAK_LOG",
            "Route blocked",
            WorkIssue.Retry.REOBSERVE_ACTION);
    wait.defer("wall-step", temporary, "unchanged", 5000);
    assertTrue(wait.waiting("wall-step", "unchanged", 4999));
    assertFalse(wait.waiting("wall-step", "unchanged", 5000));
    wait.defer("wall-step", temporary, "unchanged", 5000);
    assertFalse(wait.waiting("replacement-job", "unchanged", 1000));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            wait.defer(
                "wall-step",
                new WorkIssue(
                    WorkIssue.Action.CONSTRUCTION,
                    target,
                    "",
                    "Protected",
                    WorkIssue.Retry.REPLAN_TASK),
                "unchanged",
                5000));
  }
}
