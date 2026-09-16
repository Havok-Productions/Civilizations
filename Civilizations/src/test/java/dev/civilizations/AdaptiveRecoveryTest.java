package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import dev.civilizations.design.*;
import dev.civilizations.navigation.SurfaceEscape;
import java.util.*;
import org.junit.jupiter.api.*;

class AdaptiveRecoveryTest {
  @Test
  @Tag("navigation")
  void surfaceEscapeDetoursAndReobservesChangedRouteInsteadOfDiving() {
    Pos start = new Pos(0, 65, 0), shore = new Pos(4, 66, 0);
    Set<Pos> wet = new HashSet<>();
    for (int x = 0; x <= 3; x++) for (int z = 0; z <= 2; z++) wet.add(new Pos(x, 65, z));
    wet.remove(new Pos(2, 65, 0));
    wet.remove(new Pos(2, 65, 1));
    var route =
        SurfaceEscape.find(
            start,
            shore,
            20,
            p -> {
              if (p.equals(shore)) return SurfaceEscape.Cell.LAND;
              if (wet.contains(p) || wet.contains(p.add(0, -1, 0))) return SurfaceEscape.Cell.WATER;
              return SurfaceEscape.Cell.BLOCKED;
            });
    assertFalse(route.steps().isEmpty(), route.reason());
    assertEquals(shore, route.steps().getLast());
    assertTrue(route.steps().stream().anyMatch(p -> p.z() == 2));
    assertTrue(route.steps().stream().allMatch(p -> p.y() >= 65));
    var blocked =
        SurfaceEscape.find(
            start,
            shore,
            20,
            p -> p.equals(start) ? SurfaceEscape.Cell.WATER : SurfaceEscape.Cell.BLOCKED);
    assertTrue(blocked.steps().isEmpty());
    assertEquals("no_observed_surface_exit", blocked.reason());
  }

  @Test
  @Tag("design")
  void shiftedWallFindsDryNeighborhoodWhenCenteredSquaresCrossWater() {
    Settlement v = CoreTest.village();
    TerrainWithPond terrain = new TerrainWithPond();
    var failures = new ArrayList<Map<String, Object>>();
    var examples =
        SiteObservations.candidates(
            terrain, v, p -> false, Set.of("wall"), new TreeMap<>(), failures);
    assertFalse(examples.isEmpty(), failures.toString());
    Blueprint wall = (Blueprint) examples.getFirst().get("blueprint");
    assertTrue(DesignCompiler.insideWall(wall, v.center(), v.center()));
    assertTrue(wall.points().stream().allMatch(p -> p.x() < 2), wall.toString());
    assertFalse(failures.isEmpty());
    assertTrue(failures.toString().contains("WATER"));
    assertDoesNotThrow(
        () ->
            new DesignCompiler()
                .compile(wall, terrain, v.center(), "wall", p -> false, List.of(v.center())));
  }

  static class TerrainWithPond extends CoreTest.Flat {
    public String type(Pos p) {
      return p.x() >= 3 && p.y() == 64 ? "WATER" : super.type(p);
    }
  }

  @Test
  @Tag("design")
  @Tag("tasks")
  @Tag("interaction")
  void exposedStonePreparationCreatesMiningPrerequisitesAndPreciseTaskBlockers() {
    var terrain =
        new CoreTest.Flat() {
          public String type(Pos p) {
            return (p.x() == 8 || p.x() == 9) && p.z() == 8 && p.y() == 65
                ? "STONE"
                : super.type(p);
          }

          public int height(int x, int z) {
            return (x == 8 || x == 9) && z == 8 ? 65 : 64;
          }
        };
    var v = CoreTest.village();
    var design =
        new DesignCompiler()
            .compile(
                DesignTest.house(7, 7, 5, 5, "north"),
                terrain,
                v.center(),
                "design-test",
                p -> false,
                List.of());
    Job mine =
        design.jobs().stream().filter(j -> j.kind == Job.Kind.MINE).findFirst().orElseThrow();
    assertEquals("STONE", mine.expected);
    assertEquals(2, design.jobs().stream().filter(j -> j.kind == Job.Kind.MINE).count());
    assertTrue(
        design.jobs().stream()
            .filter(j -> j.kind == Job.Kind.PLACE)
            .allMatch(j -> j.phase > mine.phase));
    v.addProject("design-test", design.jobs());
    Job build =
        design.jobs().stream().filter(j -> j.kind == Job.Kind.PLACE).findFirst().orElseThrow();
    assertTrue(v.unavailableReason(build.id, "w", 100).contains("unfinished_preparation_phase"));
    assertEquals("", v.unavailableReason(mine.id, "w", 100));
    assertTrue(v.claim(mine.id, "other", 100));
    assertTrue(v.unavailableReason(mine.id, "w", 100).contains("claimed_by=other"));
  }
}
