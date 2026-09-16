package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import dev.civilizations.design.*;
import java.util.*;
import org.junit.jupiter.api.*;

class DesignTrialTest {
  static Blueprint wall() {
    return new Blueprint(
        "wall",
        "Test a rejected defense",
        8,
        6,
        0,
        0,
        1,
        "north",
        List.of(
            new Blueprint.Point(6, 6),
            new Blueprint.Point(10, 6),
            new Blueprint.Point(10, 10),
            new Blueprint.Point(6, 10)));
  }

  @Test
  @Tag("design")
  @Tag("tasks")
  @Tag("interaction")
  void explicitTrialQueuesRejectedPurposeWithoutCountingApprovalAsCompletedConstruction() {
    var v = CoreTest.village();
    var p = DesignProposals.retain(v, wall(), v.center(), 0);
    assertFalse(DesignProposals.admit(v, p, new CoreTest.Flat(), q -> false, 2, 0).accepted());
    var trial =
        DesignProposals.trial(v, v.proposals().getFirst(), new CoreTest.Flat(), q -> false, 100);
    assertTrue(trial.admission().accepted(), trial.admission().proposal().reason());
    assertTrue(trial.warnings().stream().anyMatch(w -> w.contains("encloses none")));
    assertEquals(16, v.jobs().size());
    assertTrue(v.jobs().stream().noneMatch(j -> j.complete));
    assertTrue(v.proposals().isEmpty());
    assertFalse(v.allComplete(trial.admission().design().project()));
    // A repeated callback cannot add duplicate construction.
    DesignProposals.trial(v, p, new CoreTest.Flat(), q -> false, 200);
    assertEquals(16, v.jobs().size());
  }

  @Test
  @Tag("design")
  @Tag("tasks")
  @Tag("interaction")
  void trialRetainsInvalidUnobservedOrProtectedPlansWithoutInventingExecutableWork() {
    for (String failure : List.of("unknown", "protected", "geometry")) {
      var v = CoreTest.village();
      var b =
          failure.equals("geometry")
              ? new Blueprint(
                  "wall",
                  "Malformed defense",
                  6,
                  6,
                  0,
                  0,
                  1,
                  "north",
                  List.of(new Blueprint.Point(6, 6)))
              : wall();
      var p = DesignProposals.retain(v, b, v.center(), 0);
      var terrain =
          new CoreTest.Flat() {
            public boolean available(int x, int z) {
              return !failure.equals("unknown");
            }
          };
      var result = DesignProposals.trial(v, p, terrain, q -> failure.equals("protected"), 100);
      assertFalse(result.admission().accepted(), failure);
      assertTrue(v.jobs().isEmpty(), failure);
      assertEquals(1, v.proposals().size());
      assertTrue(
          v.proposals().getFirst().needsSalvage(),
          "Failed trial must return to automatic revision");
      assertTrue(
          result
              .admission()
              .proposal()
              .reason()
              .contains("Trial could not produce executable jobs"));
    }
  }

  @Test
  @Tag("design")
  void trialSurveysActualFootprintWithoutSpendingThousandsOfChunksOnAnEmptyCorridor() {
    var b =
        new Blueprint(
            "wall",
            "Distant recorded layout",
            4106,
            1286,
            0,
            0,
            1,
            "north",
            List.of(
                new Blueprint.Point(4105, 1286),
                new Blueprint.Point(4108, 1286),
                new Blueprint.Point(4108, 1289),
                new Blueprint.Point(4105, 1289)));
    var area = DesignSurvey.trial(b, new Pos(-4134, 73, -1341));
    assertEquals(new Pos(-28, 73, -54), area.center());
    assertEquals(32, area.radius());
  }
}
