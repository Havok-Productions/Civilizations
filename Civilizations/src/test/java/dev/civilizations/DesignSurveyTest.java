package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import dev.civilizations.design.*;
import java.util.*;
import org.junit.jupiter.api.*;

class DesignSurveyTest {
  private boolean includes(DesignSurvey area, Pos p) {
    return Math.abs((long) p.x() - area.center().x()) <= area.radius()
        && Math.abs((long) p.z() - area.center().z()) <= area.radius();
  }

  @Test
  @Tag("design")
  @Tag("navigation")
  @Tag("interaction")
  void surveyCoversNegativeMineDirectionAndAccessOriginWithoutMirroringEmptyTerrain() {
    Pos origin = new Pos(-4134, 73, -1341);
    Blueprint mine = new Blueprint("mine", "Obtain coal", -40, -60, 12, 6, 0, "west", List.of());
    var area = DesignSurvey.proposal(mine, origin);
    assertEquals(new Pos(-4163, 73, -1371), area.center());
    assertEquals(36, area.radius());
    assertTrue(includes(area, origin));
    assertTrue(includes(area, origin.add(-58, 0, -60)));
    assertTrue(includes(area, origin.add(-39, 0, -60)));
    assertThrows(
        ArithmeticException.class,
        () -> DesignSurvey.proposal(mine, new Pos(Integer.MIN_VALUE, 65, 0)));
  }

  @Test
  @Tag("design")
  @Tag("settlements")
  @Tag("interaction")
  void recoverySurveysKnownBedAndStorageNeighborhoodsBeyondTheOldLocalMap() {
    Settlement v = CoreTest.village();
    Pos origin = new Pos(-4134, 73, -1341);
    Pos bed = origin.add(-26, -4, -52), chest = origin.add(-134, 0, 22);
    v.beds(List.of(bed));
    v.chest(chest);
    var data = v.snapshot();
    data.center = origin;
    v = new Settlement(data);
    var area = DesignSurvey.neighborhood(v, origin);
    for (Pos hub : List.of(origin, bed, chest))
      for (int dx : new int[] {-26, 26})
        for (int dz : new int[] {-26, 26}) assertTrue(includes(area, hub.add(dx, 0, dz)));
    assertTrue(area.radius() < 134 + 26, "Offset survey avoids a mirrored empty half");
  }
}
