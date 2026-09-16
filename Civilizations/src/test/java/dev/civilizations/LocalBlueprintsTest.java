package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import dev.civilizations.design.*;
import java.util.*;
import org.junit.jupiter.api.*;

class LocalBlueprintsTest {
  @Test
  @Tag("design")
  void remoteLayoutsMoveLocallyWithoutChangingDimensionsOrRelativeGeometry() {
    var v = CoreTest.village();
    for (String kind : List.of("house", "farm", "mine", "path", "lights")) {
      var points =
          kind.equals("path") || kind.equals("lights")
              ? List.of(new Blueprint.Point(4000, 2000), new Blueprint.Point(4004, 2000))
              : kind.equals("house")
                  ? List.of(new Blueprint.Point(4001, 2001))
                  : List.<Blueprint.Point>of();
      var b = new Blueprint(kind, "Serve local villagers", 4000, 2000, 5, 5, 3, "north", points);
      var fixed = LocalBlueprints.resolve(v, b, v.center());
      assertTrue(fixed.changed(), kind);
      assertEquals(b.width(), fixed.blueprint().width());
      assertEquals(b.depth(), fixed.blueprint().depth());
      assertEquals(b.height(), fixed.blueprint().height());
      assertEquals(b.purpose(), fixed.blueprint().purpose());
      assertTrue(Math.abs(fixed.blueprint().x()) < 10, kind);
      for (int i = 0; i < points.size(); i++) {
        assertEquals(
            points.get(i).x() - b.x(),
            fixed.blueprint().points().get(i).x() - fixed.blueprint().x());
        assertEquals(
            points.get(i).z() - b.z(),
            fixed.blueprint().points().get(i).z() - fixed.blueprint().z());
      }
      assertFalse(
          LocalBlueprints.resolve(v, fixed.blueprint(), v.center()).changed(),
          "No repeated drifting");
    }
  }

  @Test
  @Tag("design")
  @Tag("settlements")
  @Tag("interaction")
  void coordinatesAlreadyNearAnotherInhabitedNeighborhoodArePreserved() {
    var v = CoreTest.village();
    v.beds(List.of(new Pos(4000, 65, 2000)));
    var b = DesignTest.house(4005, 2005, 5, 5, "north");
    assertFalse(LocalBlueprints.resolve(v, b, v.center()).changed());
    var longLocalPath =
        new Blueprint(
            "path",
            "Expand a connected local route",
            0,
            0,
            1,
            0,
            0,
            "north",
            List.of(new Blueprint.Point(0, 0), new Blueprint.Point(80, 0)));
    assertFalse(
        LocalBlueprints.resolve(v, longLocalPath, v.center()).changed(),
        "Relocation is not a numeric allowed range");
  }

  @Test
  @Tag("design")
  void explicitWorldCoordinatesAndAccidentalWorldOffsetsResolveOnlyOnce() {
    var data = CoreTest.village().snapshot();
    data.center = new Pos(-4000, 65, -1000);
    var v = new Settlement(data);
    var worldAsOffset = DesignTest.house(-3990, -990, 5, 5, "north");
    var corrected = LocalBlueprints.resolve(v, worldAsOffset, v.center());
    assertEquals(10, corrected.blueprint().x());
    assertEquals(10, corrected.blueprint().z());
    assertTrue(corrected.reason().contains("world coordinates supplied"));
    assertFalse(LocalBlueprints.resolve(v, corrected.blueprint(), v.center()).changed());
    var saved = DesignProposals.retain(v, worldAsOffset, v.center(), 0);
    var local = LocalBlueprints.apply(v, saved);
    assertEquals(saved.id(), local.id());
    assertEquals(saved.original(), local.original());
    assertFalse(local.history().isEmpty());
    assertEquals("awaiting_validation", local.status());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"DIRT_PATH", "GRAVEL"})
  @Tag("design")
  @Tag("navigation")
  @Tag("interaction")
  void ordinaryGroundSupportsAWallWithoutBeingExcavated(String material) {
    var terrain =
        new CoreTest.Flat() {
          public String type(Pos p) {
            return p.y() == 64 ? material : super.type(p);
          }
        };
    var b =
        new Blueprint(
            "wall",
            "Protect local workers",
            0,
            -2,
            0,
            0,
            1,
            "north",
            List.of(
                new Blueprint.Point(-2, -2),
                new Blueprint.Point(2, -2),
                new Blueprint.Point(2, 2),
                new Blueprint.Point(-2, 2)));
    var compiled =
        new DesignCompiler()
            .compile(
                b, terrain, new Pos(0, 65, 0), "wall", p -> false, List.of(new Pos(30, 65, 30)));
    assertEquals(16, compiled.jobs().size());
    assertTrue(
        compiled.jobs().stream().allMatch(j -> j.kind == Job.Kind.PLACE && j.target.y() == 65));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DesignCompiler()
                .compile(b, terrain, new Pos(0, 65, 0), "protected", p -> true, List.of()));
  }

  @Test
  @Tag("design")
  @Tag("diagnostics")
  @Tag("interaction")
  void rejectionIncludesRealFootprintAndSupportReasonWithoutClaimingExecution() {
    var v = CoreTest.village();
    var p = DesignProposals.retain(v, DesignTest.house(8, 8, 5, 5, "north"), v.center(), 0);
    var report =
        DesignDiagnostics.rejected(
            v,
            p,
            "compilation",
            "Route grading lacks dry natural support at 8,64,8 (support=AIR; nearby={})",
            new CoreTest.Flat());
    assertEquals("terrain_or_support", report.get("category"));
    assertEquals(new LocalBlueprints.Footprint(8, 8, 12, 12), report.get("world_footprint"));
    assertEquals(false, report.get("execution_started"));
    assertTrue(report.get("reason").toString().contains("support=AIR"));
    assertTrue(FailureEvents.isFailure("design", report));
  }
}
