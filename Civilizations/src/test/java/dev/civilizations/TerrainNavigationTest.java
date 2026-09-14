package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import dev.civilizations.navigation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TerrainNavigationTest {
  @org.junit.jupiter.api.Tag("navigation")
  @Test
  void naturalProofRejectsSoilUnderStructuresAndPersistentLeaves() {
    Map<Pos, String> blocks = new HashMap<>();
    Pos p = new Pos(0, 1, 0);
    dev.civilizations.world.Terrain t =
        new dev.civilizations.world.Terrain() {
          public int height(int x, int z) {
            return 0;
          }

          public boolean available(int x, int z) {
            return true;
          }

          public String type(Pos q) {
            return blocks.getOrDefault(q, "AIR");
          }

          public String blockData(Pos q) {
            return "minecraft:oak_leaves[persistent=true]";
          }
        };
    blocks.put(p, "DIRT");
    assertTrue(dev.civilizations.world.NavigationTerrain.salvageable(t, p));
    blocks.put(p.add(0, 1, 0), "STONE_BRICKS");
    assertFalse(dev.civilizations.world.NavigationTerrain.salvageable(t, p));
    blocks.put(p.add(0, 1, 0), "GRAVEL");
    assertFalse(dev.civilizations.world.NavigationTerrain.salvageable(t, p));
    blocks.put(p, "OAK_LEAVES");
    assertFalse(dev.civilizations.world.NavigationTerrain.salvageable(t, p));
    blocks.put(p, "OAK_LOG");
    assertFalse(dev.civilizations.world.NavigationTerrain.salvageable(t, p));
  }

  @org.junit.jupiter.api.Tag("navigation")
  @Test
  void anExpandedMapFindsTheDetourOutsideTheOldRadius() {
    Map<Pos, NavigationMap.Cell> cells = new HashMap<>();
    for (int x = -32; x <= 32; x++)
      for (int z = -32; z <= 32; z++)
        for (int y = 0; y <= 4; y++)
          cells.put(
              new Pos(x, y, z),
              new NavigationMap.Cell(
                  y == 0 ? "STONE" : "AIR",
                  y == 0 ? NavigationMap.Kind.SOLID : NavigationMap.Kind.AIR));
    for (int z = -24; z <= 24; z++)
      for (int y = 1; y <= 3; y++)
        cells.put(new Pos(2, y, z), new NavigationMap.Cell("STONE", NavigationMap.Kind.SOLID));
    Pos origin = new Pos(0, 1, 0), goal = new Pos(8, 1, 0);
    var small =
        TerrainRouteSearch.search(
            new NavigationMap(origin, 20, 3, cells), origin, goal, 0, Set.of(), 0);
    assertFalse(small.reached());
    assertTrue(small.rejected().containsKey("observation_boundary"));
    var expanded =
        TerrainRouteSearch.search(
            new NavigationMap(origin, 32, 3, cells), origin, goal, 0, Set.of(), 0, 30720);
    assertTrue(expanded.reached(), expanded.reason());
    assertTrue(expanded.steps().stream().anyMatch(s -> Math.abs(s.feet().z()) > 24));
  }

  @org.junit.jupiter.api.Tag("navigation")
  @Test
  void unknownObstaclesAndFluidHaveDistinctDiagnosticsAndClutterCannotSupportWeight() {
    for (var kind :
        List.of(
            NavigationMap.Kind.UNCLASSIFIED,
            NavigationMap.Kind.OBSTACLE,
            NavigationMap.Kind.FLUID)) {
      var cells = flat();
      cells.put(start, new NavigationMap.Cell("observed", kind));
      String reason = map(cells).passage(start, true).reason();
      assertFalse(reason.contains("damaging"));
      assertFalse(map(cells).passage(start, true).allowed());
    }
    var cells = flat();
    cells.put(
        start.add(0, -1, 0), new NavigationMap.Cell("TRIPWIRE", NavigationMap.Kind.CLEARABLE));
    assertFalse(map(cells).passage(start, true).allowed());
  }

  private final Pos start = new Pos(0, 1, 0);

  private Map<Pos, NavigationMap.Cell> flat() {
    Map<Pos, NavigationMap.Cell> cells = new HashMap<>();
    for (int x = -20; x <= 20; x++)
      for (int z = -20; z <= 20; z++)
        for (int y = -2; y <= 8; y++)
          cells.put(
              new Pos(x, y, z),
              new NavigationMap.Cell(
                  y <= 0 ? "STONE" : "AIR",
                  y <= 0 ? NavigationMap.Kind.SOLID : NavigationMap.Kind.AIR));
    return cells;
  }

  private NavigationMap map(Map<Pos, NavigationMap.Cell> cells) {
    return new NavigationMap(start, 20, 5, cells);
  }

  private void wall(Map<Pos, NavigationMap.Cell> cells, int x, int z, NavigationMap.Kind kind) {
    for (int y = 1; y <= 3; y++)
      cells.put(
          new Pos(x, y, z),
          new NavigationMap.Cell(kind == NavigationMap.Kind.SOFT ? "DIRT" : "STONE", kind));
  }

  private TerrainRouteSearch.Result search(NavigationMap map, Pos goal, int clear) {
    return TerrainRouteSearch.search(map, start, goal, 0, Set.of(), clear);
  }

  @org.junit.jupiter.api.Tag("navigation")
  @Test
  void takesDetourInitiallyAwayFromGoal() {
    var cells = flat();
    for (int z = -3; z <= 3; z++) wall(cells, 2, z, NavigationMap.Kind.SOLID);
    for (int x = -2; x <= 2; x++) {
      wall(cells, x, -3, NavigationMap.Kind.SOLID);
      wall(cells, x, 3, NavigationMap.Kind.SOLID);
    }
    var route = search(map(cells), new Pos(8, 1, 0), 0);
    assertTrue(route.reached(), route.toString());
    assertTrue(route.steps().stream().anyMatch(s -> s.feet().x() < -2));
    assertTrue(route.steps().stream().allMatch(s -> s.clear().isEmpty()));
  }

  @org.junit.jupiter.api.Tag("navigation")
  @Test
  void boundedDirtSalvageCanOpenOtherwiseDisconnectedRoute() {
    var cells = flat();
    for (int z = -20; z <= 20; z++) wall(cells, 2, z, NavigationMap.Kind.SOFT);
    assertFalse(search(map(cells), new Pos(4, 1, 0), 0).reached());
    var route = search(map(cells), new Pos(4, 1, 0), 4);
    assertTrue(route.reached());
    assertTrue(route.steps().stream().mapToInt(s -> s.clear().size()).sum() <= 4);
    assertTrue(route.steps().stream().anyMatch(s -> !s.clear().isEmpty()));
  }

  @org.junit.jupiter.api.Tag("navigation")
  @Test
  void clearanceBudgetAndSolidProtectionCannotBeBypassed() {
    var cells = flat();
    for (int x = 2; x <= 5; x++)
      for (int z = -20; z <= 20; z++) wall(cells, x, z, NavigationMap.Kind.SOFT);
    assertFalse(search(map(cells), new Pos(8, 1, 0), 4).reached());
    for (int z = -20; z <= 20; z++) wall(cells, 2, z, NavigationMap.Kind.SOLID);
    assertFalse(search(map(cells), new Pos(8, 1, 0), 4).reached());
  }

  @org.junit.jupiter.api.Tag("navigation")
  @Test
  void unknownAndWaterNeverBecomeTraversable() {
    for (var kind : List.of(NavigationMap.Kind.UNKNOWN, NavigationMap.Kind.HAZARD)) {
      var cells = flat();
      for (int z = -20; z <= 20; z++)
        for (int y = 0; y <= 6; y++)
          cells.put(new Pos(2, y, z), new NavigationMap.Cell(kind.name(), kind));
      var result = search(map(cells), new Pos(4, 1, 0), 4);
      assertFalse(result.reached());
      assertFalse(result.rejected().isEmpty());
    }
  }

  @org.junit.jupiter.api.Tag("navigation")
  @org.junit.jupiter.api.Tag("diagnostics")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void rememberedTransitionsExpireOrInvalidateOnGeometryChange() {
    var cells = flat();
    var map = map(cells);
    var edge = new TerrainRouteSearch.Edge(start, start.add(1, 0, 0));
    var memory = new RouteMemory();
    memory.reject(edge, map, 1000);
    assertTrue(memory.blocked(map, 2000).contains(edge));
    var route =
        TerrainRouteSearch.search(map, start, new Pos(4, 1, 0), 0, memory.blocked(map, 2000), 0);
    assertTrue(route.reached());
    assertNotEquals(edge.to(), route.steps().getFirst().feet());
    var restored = new RouteMemory();
    restored.restore(memory.snapshot(2000), 2000);
    assertTrue(restored.blocked(map, 2000).contains(edge));
    cells.put(edge.to(), new NavigationMap.Cell("DIRT", NavigationMap.Kind.SOFT));
    assertFalse(memory.blocked(map(cells), 3000).contains(edge));
    assertTrue(restored.blocked(map, 301001).isEmpty());
  }

  @org.junit.jupiter.api.Tag("navigation")
  @Test
  void remoteGoalReturnsBoundedSegment() {
    var route = search(map(flat()), new Pos(60, 1, 0), 0);
    assertFalse(route.reached());
    assertEquals("local_segment", route.reason());
    assertFalse(route.steps().isEmpty());
    assertTrue(route.steps().stream().allMatch(s -> Math.abs(s.feet().x()) <= 20));
  }

  @org.junit.jupiter.api.Tag("navigation")
  @Test
  void jumpRequiresHeadroomAndStairsCanClimb() {
    var cells = flat();
    cells.put(new Pos(1, 1, 0), new NavigationMap.Cell("STONE", NavigationMap.Kind.SOLID));
    assertTrue(search(map(cells), new Pos(1, 2, 0), 0).reached());
    cells.put(new Pos(1, 3, 0), new NavigationMap.Cell("STONE", NavigationMap.Kind.SOLID));
    assertFalse(search(map(cells), new Pos(1, 2, 0), 0).reached());
  }
}
