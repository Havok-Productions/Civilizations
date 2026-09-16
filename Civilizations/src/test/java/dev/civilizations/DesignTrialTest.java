package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import dev.civilizations.design.*;
import java.util.*;
import org.junit.jupiter.api.*;

class DesignTrialTest {
  @Test
  @Tag("design")
  @Tag("inference")
  @Tag("diagnostics")
  @Tag("interaction")
  void trialRelocatesRemoteProposalBeforeSurveyAndPreservesItsIdentity(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    var v = CoreTest.village();
    v.enroll("worker", 5);
    var distant =
        new Blueprint(
            "wall",
            "Local defense",
            1002,
            1000,
            0,
            0,
            1,
            "north",
            List.of(
                new Blueprint.Point(1000, 1000),
                new Blueprint.Point(1004, 1000),
                new Blueprint.Point(1004, 1004),
                new Blueprint.Point(1000, 1004)));
    var p = DesignProposals.retain(v, distant, v.center(), 0);
    var captures = new ArrayList<DesignSurvey>();
    var backend =
        new dev.civilizations.ai.ModelBackend() {
          public boolean ready() {
            return false;
          }

          public String status() {
            return "offline";
          }

          public String complete(String system, String user) {
            throw new AssertionError("Coordinate repair must not call a model");
          }

          public void close() {}
        };
    try (var queue = new dev.civilizations.ai.InferenceQueue(backend, 4);
        var coordinator =
            new DesignCoordinator(
                queue,
                new VillageConnections(),
                (world, center, radius) -> {
                  captures.add(new DesignSurvey(center, radius));
                  return java.util.concurrent.CompletableFuture.completedFuture(
                      new CoreTest.Flat());
                },
                Runnable::run,
                () -> List.of(v),
                directory,
                1000,
                2,
                message -> {})) {
      var result =
          coordinator
              .trial(v, null, p, "worker", v.center(), () -> true)
              .get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertTrue(result.admission().accepted(), result.admission().proposal().reason());
      assertEquals(List.of(new DesignSurvey(v.center(), 32)), captures);
      assertEquals(DesignProposals.project(p), result.admission().design().project());
      assertTrue(v.jobs().stream().allMatch(j -> j.target.horizontal2(v.center()) < 20));
      assertTrue(
          java.nio.file.Files.readString(directory.resolve(v.id() + "-coordinate-recovery.json"))
              .contains("Translated remote"));
      assertEquals(p.original(), result.admission().proposal().original());
    }
  }

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
