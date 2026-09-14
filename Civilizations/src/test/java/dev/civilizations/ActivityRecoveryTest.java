package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import dev.civilizations.design.*;
import dev.civilizations.navigation.*;
import java.util.*;
import org.junit.jupiter.api.*;

final class ActivityRecoveryTest {
  @Test
  @Tag("navigation")
  void mapCapacityFailureReturnsToTheWorkerInsteadOfLeavingItPending(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
    var archive = new NavigationArchive(directory, message -> {});
    try (var service =
        new dev.civilizations.world.NavigationService(
            null, Runnable::run, archive, Integer.MAX_VALUE, true)) {
      // Repeated failure must also release admission; the fifth attempt cannot become queue_full.
      for (int attempt = 0; attempt < 5; attempt++) {
        var result =
            assertDoesNotThrow(
                () -> service.request(null, null, "worker", new Pos(0, 0, 0), new Pos(1, 0, 0), 0));
        var error = assertThrows(java.util.concurrent.CompletionException.class, result::join);
        assertTrue(error.getCause().getMessage().contains("snapshot_resource_budget_exceeded"));
      }
    }
  }

  @Test
  @Tag("design")
  @Tag("tasks")
  @Tag("interaction")
  void localWallCanProtectOneNeighborhoodWithoutEnclosingDistantMergedLandmarks() {
    var wall =
        new Blueprint(
            "wall",
            "Protect occupied storage",
            32,
            -3,
            0,
            0,
            3,
            "north",
            List.of(
                new Blueprint.Point(29, -3),
                new Blueprint.Point(35, -3),
                new Blueprint.Point(35, 3),
                new Blueprint.Point(29, 3)));
    var compiled =
        new DesignCompiler()
            .compile(
                wall,
                new CoreTest.Flat(),
                new Pos(0, 65, 0),
                "design-wall-local",
                p -> false,
                List.of(new Pos(32, 65, 0), new Pos(-150, 65, 0)));
    assertFalse(compiled.jobs().isEmpty());
    Settlement v = CoreTest.village();
    v.enroll("worker", 20);
    for (int i = 0; i < 2; i++) {
      String id = "house-" + i;
      Job j =
          new Job(
              Job.Kind.PLACE,
              id,
              new Pos(-10 - i, 65, 0),
              new Pos(-10 - i, 65, 1),
              "OAK_PLANKS",
              "AIR",
              null);
      assertTrue(
          v.addDesign(
              new DesignRecord(id, "house", "shelter", "{}", Map.of(), 1, 0),
              List.of(j),
              Set.of(j.target),
              2));
    }
    assertTrue(DesignNeeds.allowed(v, 2).contains("wall"));
    assertTrue(
        v.addDesign(
            new DesignRecord(
                "design-wall-local",
                "wall",
                "defense",
                new com.google.gson.Gson().toJson(wall),
                compiled.materials(),
                compiled.jobs().size(),
                0,
                new Pos(0, 65, 0)),
            compiled.jobs(),
            compiled.reservations(),
            2));
  }

  @Test
  @Tag("design")
  @Tag("diagnostics")
  void failedSitesProvideReasonsInsteadOfSilentlyDisappearing() {
    var v = CoreTest.village();
    v.enroll("worker", 20);
    Map<String, Integer> rejected = new TreeMap<>();
    var examples =
        SiteObservations.candidates(new CoreTest.Flat(), v, p -> true, Set.of("wall"), rejected);
    assertTrue(examples.isEmpty());
    assertFalse(rejected.isEmpty());
    assertFalse(Blueprint.SCHEMA.contains("maximum"));
    var proposal =
        Blueprint.parse(
            "{\"kind\":\"house\",\"purpose\":\"Larger"
                + " shelter\",\"x\":80,\"z\":0,\"width\":12,\"depth\":12,\"height\":5,\"direction\":\"north\",\"points\":[]}");
    assertEquals(80, proposal.x());
    assertTrue(proposal.surveyRadius() > 80);
  }

  @Test
  @Tag("navigation")
  void surfaceWaterHasAnExitButDryRoutesDoNotEnterIt() {
    Map<Pos, NavigationMap.Cell> cells = new HashMap<>();
    for (int x = 0; x <= 4; x++) {
      cells.put(new Pos(x, 0, 0), new NavigationMap.Cell("STONE", NavigationMap.Kind.SOLID));
      cells.put(
          new Pos(x, 1, 0),
          new NavigationMap.Cell(
              x < 2 ? "WATER" : "AIR", x < 2 ? NavigationMap.Kind.FLUID : NavigationMap.Kind.AIR));
      cells.put(new Pos(x, 2, 0), new NavigationMap.Cell("AIR", NavigationMap.Kind.AIR));
      cells.put(new Pos(x, 3, 0), new NavigationMap.Cell("AIR", NavigationMap.Kind.AIR));
    }
    var map = new NavigationMap(new Pos(0, 1, 0), 8, 4, cells);
    assertTrue(
        TerrainRouteSearch.search(map, new Pos(0, 1, 0), new Pos(4, 1, 0), 0, Set.of(), 0)
            .reached());
    assertFalse(
        TerrainRouteSearch.search(map, new Pos(4, 1, 0), new Pos(0, 1, 0), 0, Set.of(), 0)
            .reached());
  }
}
