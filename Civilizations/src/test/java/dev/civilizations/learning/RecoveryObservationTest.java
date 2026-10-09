package dev.civilizations.learning;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import dev.civilizations.ai.*;
import dev.civilizations.core.Pos;
import dev.civilizations.navigation.NavigationMap;
import dev.coreai.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RecoveryObservationTest {
  @TempDir Path root;

  @Tag("navigation")
  @Tag("coreai")
  @Tag("inference")
  @Tag("interaction")
  @Test
  void naturalObstructionFactsSurviveNearbyWallCrowdingAndCompaction() {
    Pos origin = new Pos(100, 64, -70);
    var cells = new HashMap<Pos, NavigationMap.Cell>();
    // Eight closer wall cells previously hid every material name for the removable obstacle.
    for (int x = -1; x <= 1; x++)
      for (int z = -1; z <= 1; z++)
        if (x != 0 || z != 0)
          cells.put(
              origin.add(x, 0, z),
              new NavigationMap.Cell(
                  "STONE",
                  NavigationMap.Kind.SOLID,
                  "minecraft:stone",
                  "requires_mining_task_and_suitable_tool_not_CLEAR"));
    String leaves = "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]";
    for (int y = 0; y <= 1; y++)
      cells.put(
          origin.add(2, y, 0),
          new NavigationMap.Cell(
              "OAK_LEAVES",
              NavigationMap.Kind.SOFT,
              leaves,
              "CLEAR_within_reach_live_checks_and_retained_drops"));
    cells.put(
        origin.add(3, 0, 0),
        new NavigationMap.Cell(
            "DIRT", NavigationMap.Kind.SOLID, "minecraft:dirt", "protected_or_reserved"));
    var map = new NavigationMap(origin, 8, 10, cells);
    var context = SkillContext.create(map, origin, origin.add(6, 0, 0), 0, Map.of(), "blocked");
    assertTrue(
        RecoveryTerrainView.nearbyObstacles(map, origin).stream()
            .allMatch(value -> value.get("material").equals("STONE")));
    String report =
        ModelContext.reduce(ModelContext.compact(new Gson().toJson(context.observation())));
    var json = com.google.gson.JsonParser.parseString(report).getAsJsonObject();
    var coordinates = json.getAsJsonArray("natural_clear_candidates");
    var details = json.getAsJsonArray("natural_clear_candidates_details");
    assertEquals(2, details.size(), "Protected and unknown terrain must not become candidates");
    assertEquals(coordinates.size(), details.size());
    for (int i = 0; i < details.size(); i++) {
      var candidate = details.get(i).getAsJsonObject();
      assertEquals(coordinates.get(i), candidate.get("relative"));
      assertEquals(2, candidate.getAsJsonObject("relative").get("x").getAsInt());
      assertEquals("OAK_LEAVES", candidate.get("material").getAsString());
      assertEquals(leaves, candidate.get("block_state").getAsString());
      assertEquals("SOFT", candidate.get("map_category").getAsString());
      assertEquals(
          "CLEAR_within_reach_live_checks_and_retained_drops",
          candidate.get("removal").getAsString());
      assertTrue(candidate.get("live_checks_required").getAsBoolean());
    }
    assertFalse(json.has("program"), "Observed facts are not a supplied solution");
  }

  @Tag("navigation")
  @Tag("coreai")
  @Tag("inference")
  @Tag("interaction")
  @Test
  void elevatedGeometryAndProtectionSurviveContextCompaction() {
    Pos origin = new Pos(0, 60, 0), goal = origin.add(-11, 27, -8);
    var cells = new HashMap<Pos, NavigationMap.Cell>();
    cells.put(goal, new NavigationMap.Cell("AIR", NavigationMap.Kind.AIR));
    cells.put(
        goal.add(0, -1, 0),
        new NavigationMap.Cell(
            "STONE", NavigationMap.Kind.SOLID, "minecraft:stone", "protected_or_reserved"));
    cells.put(
        goal.add(1, 0, 0),
        new NavigationMap.Cell(
            "STONE",
            NavigationMap.Kind.SOLID,
            "minecraft:stone",
            "requires_mining_task_and_suitable_tool_not_CLEAR"));
    var map = new NavigationMap(origin, 20, 30, cells);
    var context = SkillContext.create(map, origin, goal, 12, Map.of("DIRT", 4), "blocked");
    String report = ModelContext.compact(new Gson().toJson(context.observation()));
    assertTrue(report.contains("route_height"));
    assertTrue(report.contains("support_below_focus"));
    assertTrue(
        report.contains("\"obstacle_count\":1"),
        "Floor support must not be reported as a body obstacle");
    assertTrue(report.contains("27..27"), "The elevated goal must appear in the slice");
    assertTrue(report.contains("protected_or_reserved"));
    assertTrue(report.contains("requires_mining_task"));
    assertTrue(report.contains("?"), "Missing cells remain unknown");
    assertEquals(
        '.',
        RecoveryTerrainView.symbol(
            new NavigationMap.Cell("AIR", NavigationMap.Kind.AIR, "", "protected_or_reserved")));
    var other = new HashMap<>(cells);
    other.put(
        goal.add(0, -1, 0),
        new NavigationMap.Cell(
            "STONE",
            NavigationMap.Kind.SOLID,
            "minecraft:stone",
            "requires_mining_task_and_suitable_tool_not_CLEAR"));
    assertNotEquals(map.fingerprint, new NavigationMap(origin, 20, 30, other).fingerprint);
  }

  @Tag("navigation")
  @Tag("coreai")
  @Tag("inference")
  @Tag("interaction")
  @Test
  void requestedObservationDoesNotTeachFailedBehavior() throws Exception {
    String observe =
        "{\"explanation\":\"Need elevated"
            + " geometry\",\"steps\":[{\"op\":\"OBSERVE\",\"x\":6,\"y\":27,\"z\":0,\"material\":\"\"}]}";
    String revised = observe.replace("OBSERVE", "WALK").replace("27", "0");
    assertEquals(SkillProgram.Op.OBSERVE, SkillProgram.parse(observe).steps().getFirst().op());
    var calls = new AtomicInteger();
    var reports = new CopyOnWriteArrayList<String>();
    ModelBackend backend =
        new ModelBackend() {
          public boolean ready() {
            return true;
          }

          public String status() {
            return "fixture";
          }

          public void close() {}

          public String complete(String system, String report) {
            reports.add(report);
            return calls.incrementAndGet() == 1 ? observe : revised;
          }
        };
    Pos at = new Pos(0, 60, 0), goal = at.add(6, 0, 0);
    var map = new NavigationMap(at, 20, 30, Map.of());
    var context = SkillContext.create(map, at, goal, 1, Map.of(), "blocked");
    var fresh = SkillContext.create(map, at, goal, 1, Map.of(), "requested_fresh_observation");
    try (var queue = new InferenceQueue(backend, 4);
        var experiments = new RecoveryExperiments(root, queue, () -> "fixture", s -> fail(s))) {
      long now = System.currentTimeMillis();
      var trial = experiments.request("v", "w", context, now);
      trial.program.get(2, TimeUnit.SECONDS);
      assertEquals(
          SkillProgram.parse(revised),
          experiments
              .revise(trial, fresh, Map.of("focus", at.add(6, 27, 0)), now, true)
              .get(2, TimeUnit.SECONDS));
      assertTrue(reports.getLast().contains("observation_request"));
      assertFalse(reports.getLast().contains("failed_execution"));
      assertNull(
          new SkillLibrary(root.resolve("skills")).get(context.key()),
          "An observation request is neither failed behavior nor a completed goal");
      experiments.cancel(trial, "fixture_end");
    }
  }
}
