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
