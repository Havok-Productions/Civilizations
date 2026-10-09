package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import dev.civilizations.core.Pos;
import dev.civilizations.learning.*;
import dev.civilizations.navigation.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class EmergencyRecoveryTest {
  @Test
  @Tag("navigation")
  void unchangedCrossingTimesOutDespiteRepeatedPlansAndCanRecoverAfterTerrainChanges() {
    var progress = new SurfaceProgress();
    Pos start = new Pos(0, 60, 0), shore = start.add(1, 0, 0);
    var edge = new SurfaceProgress.Edge(start, shore);
    assertFalse(progress.stalled(edge, 1.0, 100, 10000));
    for (long now = 2600; now < 10100; now += 2500)
      assertFalse(progress.stalled(new SurfaceProgress.Edge(start, shore), .98, now, 10000));
    assertTrue(progress.stalled(edge, .98, 10100, 10000));
    progress.reject(edge, "fence");
    assertTrue(progress.blocked(edge, "fence"));
    var observed =
        Map.of(
            start,
            SurfaceEscape.Cell.WATER,
            shore,
            SurfaceEscape.Cell.LAND,
            start.add(0, 0, 1),
            SurfaceEscape.Cell.WATER,
            shore.add(0, 0, 1),
            SurfaceEscape.Cell.LAND);
    var alternate =
        SurfaceEscape.find(
            start,
            shore,
            20,
            p -> observed.getOrDefault(p, SurfaceEscape.Cell.BLOCKED),
            (a, b) -> !progress.blocked(new SurfaceProgress.Edge(a, b), "fence"));
    assertEquals(start.add(0, 0, 1), alternate.steps().getFirst());
    assertFalse(progress.blocked(edge, "fence_removed"));
    assertFalse(progress.stalled(edge, 1, 20000, 10000));
    assertFalse(progress.stalled(edge, .5, 29000, 10000));
    assertFalse(progress.stalled(edge, .5, 30000, 10000));
    progress.reached(shore);
    assertFalse(progress.stalled(edge, 1, 50000, 10000));
  }

  @Test
  @Tag("navigation")
  void bodyCollisionRejectsApparentlyDryLandAndDoesNotBlacklistOtherApproaches() {
    Pos start = new Pos(0, 60, 0), falseShore = start.add(1, 0, 0), exit = start.add(-1, 0, 0);
    var result =
        SurfaceEscape.find(
            start,
            falseShore,
            20,
            p ->
                p.equals(start)
                    ? SurfaceEscape.Cell.WATER
                    : p.equals(exit) || p.equals(falseShore)
                        ? SurfaceEscape.Cell.LAND
                        : SurfaceEscape.Cell.BLOCKED,
            (a, b) -> !b.equals(falseShore));
    assertEquals(List.of(exit), result.steps());
  }

  @Test
  @Tag("navigation")
  @Tag("coreai")
  @Tag("diagnostics")
  @Tag("interaction")
  void emergencyReceiptsPersistWithoutAQueuedModelTrial(@TempDir Path root) throws Exception {
    var evidence =
        Map.of(
            "reason",
            "surface_escape_no_progress",
            "transition",
            new SurfaceProgress.Edge(new Pos(0, 60, 0), new Pos(1, 60, 0)),
            "terrain_signature",
            "fence",
            "observed_terrain",
            List.of(Map.of("state", "oak_fence")),
            "blocking_blocks",
            List.of(Map.of("material", "OAK_FENCE")));
    try (var experiments = new RecoveryExperiments(root, null, () -> "unused", s -> fail(s))) {
      experiments.emergency("v", "worker", "emergency_route_failed", evidence);
      experiments.emergency("v", "worker", "emergency_escaped", Map.of("task_retained", true));
    }
    String report = new Gson().toJson(new RecoveryDiagnostics(root).report("v"));
    assertTrue(report.contains("emergency_navigation_stall"));
    assertTrue(report.contains("OAK_FENCE"));
    assertFalse(
        report.contains("observed_terrain"), "Do not duplicate the full map in future prompts");
    assertTrue(report.contains("terrain_signature"));
    String journal = Files.readString(root.resolve("data/agents.jsonl"));
    assertTrue(journal.contains("oak_fence"));
    assertTrue(journal.contains("emergency_escaped"));
    assertTrue(journal.contains("not a learned skill success"));
    try (var files = Files.list(root.resolve("repair-proposals"))) {
      assertEquals(1, files.count());
    }
  }
}
