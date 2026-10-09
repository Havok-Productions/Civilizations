package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ProjectExecutionTest {
  private Job step(String project, int x) {
    return new Job(
        Job.Kind.PLACE, project, new Pos(x, 65, 0), new Pos(x, 65, 1), "OAK_PLANKS", "AIR", null);
  }

  @Test
  @Tag("tasks")
  void continuesProjectPrerequisitesBeforeUnrelatedSuppliedWork() {
    Job unrelated = step("lights", 1), next = step("wall", 2), other = step("house", 3);
    assertEquals(
        List.of(next, unrelated, other),
        ProjectExecution.order(List.of(unrelated, next, other), "wall", j -> j == unrelated));
    assertEquals(
        List.of(unrelated, other),
        ProjectExecution.order(List.of(other, unrelated), "wall", j -> j == unrelated),
        "An unavailable project step must not prevent other admitted work");
  }

  @Test
  @Tag("tasks")
  @Tag("settlements")
  @Tag("interaction")
  void respectsPhaseClaimsAndRetryWhileReturningToAWholeProject() {
    var village = CoreTest.village();
    village.enroll("worker", 5);
    village.enroll("peer", 5);
    Job prepare = step("design-wall", 1), build = step("design-wall", 2), other = step("other", 3);
    prepare.phase = 0;
    build.phase = 1;
    village.addProject("design-wall", List.of(prepare, build));
    village.addProject("other", List.of(other));
    village.taskProject("worker", "design-wall");
    var offers =
        TaskSelection.offered(village, "worker", new Pos(0, 65, 0), Map.of(), 100, j -> Map.of());
    assertFalse(offers.stream().anyMatch(j -> j.id.equals(build.id)));
    assertTrue(village.claim(prepare.id, "peer", 100));
    offers =
        TaskSelection.offered(village, "worker", new Pos(0, 65, 0), Map.of(), 101, j -> Map.of());
    assertEquals(other.id, ProjectExecution.order(offers, "design-wall", j -> true).getFirst().id);
    assertTrue(village.done(prepare.id, "peer"));
    offers =
        TaskSelection.offered(village, "worker", new Pos(0, 65, 0), Map.of(), 102, j -> Map.of());
    assertEquals(build.id, ProjectExecution.order(offers, "design-wall", j -> false).getFirst().id);
    assertTrue(village.claim(build.id, "worker", 103));
    village.failed(build.id, "worker", 104, "Blocked route");
    offers =
        TaskSelection.offered(village, "worker", new Pos(0, 65, 0), Map.of(), 105, j -> Map.of());
    assertFalse(offers.stream().anyMatch(j -> j.id.equals(build.id)));
    assertFalse(village.allComplete("design-wall"));
  }
}
