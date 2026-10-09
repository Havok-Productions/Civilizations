package dev.civilizations.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.Pos;
import dev.civilizations.navigation.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class ResourceSurveyTest {
  @Tag("navigation")
  @Tag("crafting")
  @Test
  void surfaceResourcesUseObservedElevationAndStopAtRoofsAndUnknownColumns() {
    Pos underground = new Pos(0, 12, 0), root = new Pos(2, 91, 0);
    Terrain terrain =
        new Terrain() {
          public boolean available(int x, int z) {
            return z == 0 && (x == 2 || x == 3);
          }

          public int height(int x, int z) {
            return x == 2 ? 112 : 95;
          }

          public String type(Pos p) {
            if (!available(p.x(), p.z())) throw new AssertionError("Read unobserved column");
            if (p.x() == 3) return p.y() == 95 ? "OAK_PLANKS" : "OAK_LOG";
            if (p.y() == 112) return "OAK_LEAVES";
            if (p.y() >= 91 && p.y() < 112) return "OAK_LOG";
            return "GRASS_BLOCK";
          }
        };
    var result = SurfaceResources.observe(terrain, underground, 3, "OAK_LOG");
    assertEquals(List.of(root), result.sources());
    assertEquals(2, result.observedColumns());
    assertEquals(47, result.missingColumns());
    assertThrows(
        IllegalArgumentException.class,
        () -> SurfaceResources.observe(terrain, underground, 3, "COAL"));
    assertFalse(SurfaceResources.applicable("DIAMOND"));
    Terrain missing =
        new Terrain() {
          public boolean available(int x, int z) {
            return false;
          }

          public int height(int x, int z) {
            throw new AssertionError("Unknown height read");
          }

          public String type(Pos p) {
            throw new AssertionError("Unknown block read");
          }
        };
    assertEquals(0, SurfaceResources.observe(missing, underground, 3, "LOG").observedColumns());
  }

  @Tag("navigation")
  @Tag("crafting")
  @Test
  void queuedCaptureIsNotResourceAbsenceAndSourcesSurviveCompletion() {
    Pos at = new Pos(0, 65, 0), log = at.add(3, 0, 0);
    var capture = new CompletableFuture<NavigationService.Plan>();
    var survey = new ResourceSurvey(p -> capture, (type, data) -> {});
    assertTrue(survey.search("OAK_LOG", at, 1000).isEmpty());
    assertTrue(survey.awaitingObservation());
    assertTrue(survey.search("OAK_LOG", at, 2000).isEmpty());
    assertTrue(survey.awaitingObservation());
    var map =
        new NavigationMap(
            at,
            20,
            12,
            Map.of(
                at, new NavigationMap.Cell("AIR", NavigationMap.Kind.AIR),
                log, new NavigationMap.Cell("OAK_LOG", NavigationMap.Kind.CLEARABLE)));
    capture.complete(new NavigationService.Plan("id", "map", map, null, at, 0, 2500, List.of()));
    assertEquals(List.of(log), survey.search("OAK_LOG", at, 3000));
    assertFalse(survey.awaitingObservation());
  }

  @Tag("navigation")
  @Tag("crafting")
  @Test
  void failedAndUnobservedCapturesDoNotProveMaterialsAbsent() {
    Pos at = new Pos(0, 65, 0);
    var capture = new CompletableFuture<NavigationService.Plan>();
    var survey = new ResourceSurvey(p -> capture, (type, data) -> {});
    survey.search("COAL", at, 1000);
    capture.completeExceptionally(new IllegalStateException("region unavailable"));
    survey.search("COAL", at, 2000);
    assertTrue(survey.awaitingObservation());
    var unknown = new NavigationMap(at, 20, 12, Map.of());
    var second =
        new ResourceSurvey(
            p ->
                CompletableFuture.completedFuture(
                    new NavigationService.Plan("id", "map", unknown, null, at, 0, 0, List.of())),
            (t, d) -> {});
    second.search("COAL", at, 1000);
    assertTrue(second.search("COAL", at, 2000).isEmpty());
    assertTrue(second.awaitingObservation());
  }
}
