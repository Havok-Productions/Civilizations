package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import dev.civilizations.ai.*;
import dev.civilizations.core.*;
import dev.civilizations.design.*;
import dev.civilizations.world.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesignTest {
  @TempDir Path tmp;

  @org.junit.jupiter.api.Tag("runtime")
  @Test
  void privateRuntimeChoosesAnAvailablePortAndBoundsItsSearch() throws Exception {
    assertEquals(8644, LocalPorts.choose(8643, p -> p == 8644));
    assertEquals(1024, LocalPorts.choose(65535, p -> p == 1024));
    AtomicInteger attempts = new AtomicInteger();
    assertThrows(
        java.io.IOException.class,
        () ->
            LocalPorts.choose(
                8643,
                p -> {
                  attempts.incrementAndGet();
                  return false;
                }));
    assertEquals(16, attempts.get());
    assertThrows(java.io.IOException.class, () -> LocalPorts.choose(80, p -> true));
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void resourceSurveyDoesNotAddFixedLayoutsInAdaptiveMode() {
    var survey =
        new Planner().plan(new CoreTest.Flat(), new Pos(0, 65, 0), 12, 3, 6, 6, p -> true, false);
    assertTrue(survey.wall().isEmpty());
    assertTrue(survey.house().isEmpty());
    assertTrue(survey.mine().isEmpty());
    assertTrue(survey.lights().isEmpty());
    assertNotNull(survey.chest());
  }

  @org.junit.jupiter.api.Tag("design")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void supplyMineCanUnblockFullProjectCapacityAndPauseStopsNewDesigns() {
    Settlement v = CoreTest.village();
    v.enroll("a", 5);
    for (int i = 0; i < 2; i++) {
      String id = "design-house-" + i;
      Job job =
          new Job(
              Job.Kind.PLACE,
              id,
              new Pos(10 + i, 65, 10),
              new Pos(10 + i, 65, 9),
              "OAK_PLANKS",
              "AIR",
              null);
      assertTrue(
          v.addDesign(
              new DesignRecord(id, "house", "Need shelter", "{}", Map.of("OAK_LOG", 1), 1, 0),
              List.of(job),
              Set.of(job.target),
              2));
    }
    assertEquals(Set.of("mine", "wall"), DesignNeeds.allowed(v, 2));
    v.paused(true);
    assertTrue(DesignNeeds.allowed(v, 2).isEmpty());
  }

  static Blueprint.Point p(int x, int z) {
    return new Blueprint.Point(x, z);
  }

  static Blueprint house(int x, int z, int w, int d, String direction) {
    return new Blueprint(
        "house", "Provide beds for villagers", x, z, w, d, 3, direction, List.of());
  }

  static Blueprint wall() {
    return new Blueprint(
        "wall",
        "Keep mobs out",
        0,
        -8,
        0,
        0,
        3,
        "north",
        List.of(p(-8, -8), p(8, -8), p(8, 8), p(2, 8), p(2, 3), p(-8, 3)));
  }

  static DesignCompiler.Result compile(Blueprint b, Terrain t) {
    return new DesignCompiler()
        .compile(b, t, new Pos(0, 65, 0), "design-" + b.kind() + "-test", q -> false, List.of());
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void wallsUseReachableStandingGroundOneBlockBelowContour() {
    Blueprint b =
        new Blueprint(
            "wall",
            "Protect a raised perimeter",
            0,
            -2,
            0,
            0,
            3,
            "north",
            List.of(p(-2, -2), p(2, -2), p(2, 2), p(-2, 2)));
    Terrain terrain =
        new CoreTest.Flat() {
          @Override
          public int height(int x, int z) {
            return Math.max(Math.abs(x), Math.abs(z)) == 2 ? 65 : 64;
          }

          @Override
          public String type(Pos p) {
            return p.y() > height(p.x(), p.z()) ? "AIR" : "GRASS_BLOCK";
          }
        };
    var result = compile(b, terrain);
    assertEquals(46, result.jobs().size());
    assertTrue(result.jobs().stream().allMatch(j -> j.stand.y() == 65));
    assertTrue(result.jobs().stream().allMatch(j -> j.target.distance2(j.stand) <= 21));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DesignCompiler()
                .compile(
                    b,
                    terrain,
                    new Pos(0, 65, 0),
                    "protected-wall",
                    p -> p.equals(new Pos(2, 66, 0)),
                    List.of()));
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void housesUseModelDimensionsAndEntranceSideAndHaveRealMaterialRequirements() {
    var small = compile(house(3, 3, 5, 5, "north"), new CoreTest.Flat());
    var wide = compile(house(3, 3, 7, 6, "east"), new CoreTest.Flat());
    assertTrue(wide.jobs().size() > small.jobs().size());
    Job gate =
        wide.jobs().stream()
            .filter(j -> j.material.equals("OAK_FENCE_GATE"))
            .findFirst()
            .orElseThrow();
    assertEquals(new Pos(9, 66, 6), gate.target);
    assertTrue(gate.blockData.contains("east"));
    assertTrue(wide.materials().get("OAK_LOG") > small.materials().get("OAK_LOG"));
    assertEquals(3, wide.materials().get("WHITE_WOOL"));
    assertEquals(1, wide.materials().get("COAL"));
    assertEquals(1, wide.jobs().stream().filter(j -> j.material.equals("TORCH")).count());
    assertTrue(
        wide.reservations().contains(new Pos(5, 67, 5)),
        "Interior air must be reserved as well as placed blocks");
    assertTrue(wide.jobs().stream().allMatch(j -> j.target.distance2(j.stand) <= 21));
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void rejectWetObstructedProtectedOrOversizedHousing() {
    CoreTest.Flat wet = new CoreTest.Flat();
    wet.overrides.put(new Pos(5, 64, 5), "WATER");
    assertThrows(IllegalArgumentException.class, () -> compile(house(3, 3, 5, 5, "north"), wet));
    CoreTest.Flat built = new CoreTest.Flat();
    built.overrides.put(new Pos(5, 66, 5), "OAK_PLANKS");
    assertThrows(IllegalArgumentException.class, () -> compile(house(3, 3, 5, 5, "north"), built));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DesignCompiler()
                .compile(
                    house(3, 3, 5, 5, "north"),
                    new CoreTest.Flat(),
                    new Pos(0, 65, 0),
                    "design-house-protected",
                    q -> q.equals(new Pos(4, 64, 4)),
                    List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> compile(house(3, 3, 100, 5, "north"), new CoreTest.Flat()));
    assertThrows(
        IllegalArgumentException.class,
        () -> compile(house(Integer.MAX_VALUE, 3, 5, 5, "north"), new CoreTest.Flat()));
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void houseRejectsBedsOutsideInteriorAndBlockedDoorway() {
    Blueprint outside = new Blueprint("house", "Beds", 3, 3, 5, 5, 3, "north", List.of(p(3, 3)));
    assertThrows(IllegalArgumentException.class, () -> compile(outside, new CoreTest.Flat()));
    Blueprint door = new Blueprint("house", "Beds", 3, 3, 5, 5, 3, "north", List.of(p(5, 4)));
    assertThrows(IllegalArgumentException.class, () -> compile(door, new CoreTest.Flat()));
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void bentWallEnclosesVillageAndAvoidsAnObstacleOutsideItsContour() {
    CoreTest.Flat t = new CoreTest.Flat();
    t.overrides.put(new Pos(-5, 65, 6), "OAK_PLANKS");
    var result = compile(wall(), t);
    assertTrue(result.jobs().size() > 100);
    assertTrue(result.jobs().size() <= 512);
    assertEquals(
        1, result.jobs().stream().filter(j -> j.material.equals("OAK_FENCE_GATE")).count());
    assertFalse(result.reservations().contains(new Pos(-5, 65, 6)));
    assertTrue(DesignCompiler.insideWall(wall(), new Pos(0, 65, 0), new Pos(0, 65, 0)));
    assertFalse(DesignCompiler.insideWall(wall(), new Pos(0, 65, 0), new Pos(-5, 65, 6)));
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void wallRejectsMissingGateWaterSelfIntersectionAndExcludingBeds() {
    Blueprint badGate = new Blueprint("wall", "Defense", 1, 1, 0, 0, 3, "north", wall().points());
    assertThrows(IllegalArgumentException.class, () -> compile(badGate, new CoreTest.Flat()));
    Blueprint crossing =
        new Blueprint(
            "wall",
            "Defense",
            0,
            -8,
            0,
            0,
            3,
            "north",
            List.of(p(-8, -8), p(8, -8), p(8, 8), p(0, 8), p(0, -12), p(-8, -12)));
    assertThrows(IllegalArgumentException.class, () -> compile(crossing, new CoreTest.Flat()));
    CoreTest.Flat wet = new CoreTest.Flat();
    wet.overrides.put(new Pos(8, 64, 0), "WATER");
    assertThrows(IllegalArgumentException.class, () -> compile(wall(), wet));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DesignCompiler()
                .compile(
                    wall(),
                    new CoreTest.Flat(),
                    new Pos(0, 65, 0),
                    "design-wall-test",
                    q -> false,
                    List.of(new Pos(-5, 65, 6))));
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void pathsFollowDrawnTurnsAndDoNotRequireInventedConstructionItems() {
    Blueprint path =
        new Blueprint(
            "path",
            "Connect storage to farm",
            0,
            0,
            1,
            0,
            0,
            "north",
            List.of(p(0, 0), p(5, 0), p(5, 5)));
    var result = compile(path, new CoreTest.Flat());
    assertEquals(11, result.jobs().size());
    assertTrue(result.materials().isEmpty());
    assertTrue(
        result.jobs().stream()
            .allMatch(
                j -> j.kind == Job.Kind.PATH && TaskSelection.missing(j, Map.of()).isEmpty()));
    CoreTest.Flat wet = new CoreTest.Flat();
    wet.overrides.put(new Pos(5, 64, 3), "WATER");
    assertThrows(IllegalArgumentException.class, () -> compile(path, wet));
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void modelPlacedFarmRequiresExistingWaterAndRealSeeds() {
    Blueprint farm = new Blueprint("farm", "Grow wheat", 3, 0, 1, 3, 0, "north", List.of());
    assertThrows(IllegalArgumentException.class, () -> compile(farm, new CoreTest.Flat()));
    CoreTest.Flat t = new CoreTest.Flat();
    t.overrides.put(new Pos(5, 64, 1), "WATER");
    var result = compile(farm, t);
    assertEquals(3, result.jobs().size());
    assertEquals(Map.of("WHEAT_SEEDS", 3), result.materials());
    assertTrue(result.jobs().stream().allMatch(j -> j.kind == Job.Kind.FARM));
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void mineUsesModelEntranceAndDirectionAndPreservesDescendingOrder() {
    Blueprint mine = new Blueprint("mine", "Obtain stone", 12, 0, 3, 4, 0, "east", List.of());
    var result = compile(mine, new CoreTest.Flat());
    assertTrue(result.jobs().size() > 8);
    assertTrue(result.jobs().stream().allMatch(j -> j.kind == Job.Kind.MINE && j.target.x() >= 12));
    for (int i = 1; i < result.jobs().size(); i++)
      assertTrue(result.jobs().get(i).phase > result.jobs().get(i - 1).phase);
    CoreTest.Flat wet = new CoreTest.Flat();
    wet.overrides.put(new Pos(14, 62, 1), "WATER");
    assertThrows(IllegalArgumentException.class, () -> compile(mine, wet));
    CoreTest.Flat structure = new CoreTest.Flat();
    structure.overrides.put(new Pos(14, 64, 0), "OAK_PLANKS");
    assertThrows(IllegalArgumentException.class, () -> compile(mine, structure));
  }

  @org.junit.jupiter.api.Tag("navigation")
  @org.junit.jupiter.api.Tag("design")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void disconnectedIslandIsRejectedEvenWhenLocalBuildingSiteIsDry() {
    CoreTest.Flat island = new CoreTest.Flat();
    for (int z = -30; z <= 30; z++)
      for (int x = 0; x <= 2; x++) island.overrides.put(new Pos(x, 64, z), "WATER");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DesignCompiler()
                .compile(
                    house(10, 3, 5, 5, "north"),
                    island,
                    new Pos(-5, 65, 0),
                    "design-house-island",
                    q -> false,
                    List.of()));
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void lightEstimateReusesTorchesAndSticksAcrossSites() {
    var lights =
        compile(
            new Blueprint(
                "lights",
                "Light paths",
                0,
                0,
                0,
                0,
                0,
                "north",
                List.of(p(2, 2), p(8, 2), p(14, 2), p(20, 2), p(20, 8))),
            new CoreTest.Flat());
    assertEquals(Map.of("OAK_LOG", 1, "COAL", 2), lights.materials());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            compile(
                new Blueprint(
                    "lights", "Light paths", 0, 0, 0, 0, 0, "north", List.of(p(2, 2), p(2, 2))),
                new CoreTest.Flat()));
  }

  @org.junit.jupiter.api.Tag("design")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void designReservationsPhasesAndEstimatesPersistAndConflictingAdmissionIsAtomic()
      throws Exception {
    Settlement v = CoreTest.village();
    v.enroll("a", 5);
    Blueprint blueprint = house(3, 3, 5, 5, "north");
    var compiled = compile(blueprint, new CoreTest.Flat());
    String project = compiled.jobs().getFirst().project;
    DesignRecord record =
        new DesignRecord(
            project,
            "house",
            blueprint.purpose(),
            new Gson().toJson(blueprint),
            compiled.materials(),
            compiled.jobs().size(),
            1);
    assertTrue(v.addDesign(record, compiled.jobs(), compiled.reservations(), 2));
    assertFalse(v.claim(compiled.jobs().get(1).id, "a", 1));
    assertTrue(v.claim(compiled.jobs().getFirst().id, "a", 1));
    v.done(compiled.jobs().getFirst().id, "a");
    assertTrue(v.available(compiled.jobs().get(1).id, "a", 2));
    assertFalse(
        v.addDesign(
            new DesignRecord(
                "other",
                "house",
                "Conflicting",
                record.blueprint(),
                record.materials(),
                record.jobs(),
                2),
            compiled.jobs(),
            compiled.reservations(),
            2));
    assertEquals(1, v.designs().size());
    v.designFeedback("Accepted plan");
    StateStore store = new StateStore(tmp);
    store.save(List.of(v.snapshot()));
    Settlement restored = store.load(message -> fail(message)).getFirst();
    assertEquals(v.designs(), restored.designs());
    assertEquals(v.layoutOccupancy(), restored.layoutOccupancy());
    assertEquals(List.of("Accepted plan"), restored.designFeedback());
    assertTrue(restored.available(compiled.jobs().get(1).id, "a", 2));
  }

  @org.junit.jupiter.api.Tag("design")
  @org.junit.jupiter.api.Tag("inference")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void schemaCannotTurnIntoCommandsAndDesignUsesItsOwnStructuredResponse() {
    String json = new Gson().toJson(house(3, 3, 5, 5, "north"));
    assertEquals("house", Blueprint.parse(json).kind());
    assertThrows(Exception.class, () -> Blueprint.parse(json.replace("\"house\"", "\"command\"")));
    assertThrows(Exception.class, () -> Blueprint.parse(json.replace("\"x\":3", "\"x\":3.5")));
    assertThrows(
        Exception.class,
        () ->
            Blueprint.parse(json.substring(0, json.length() - 1) + ",\"code\":\"delete files\"}"));
    var request =
        LocalRuntime.requestBody(
            LocalRuntime.Settings.read(new YamlConfiguration()),
            "architect",
            "terrain",
            ReasoningMode.DESIGN,
            Blueprint.SCHEMA);
    assertTrue(
        request.getAsJsonObject("chat_template_kwargs").get("enable_thinking").getAsBoolean());
    assertEquals(2048, request.get("max_tokens").getAsInt());
    assertTrue(
        request
            .getAsJsonObject("response_format")
            .getAsJsonObject("json_schema")
            .getAsJsonObject("schema")
            .getAsJsonObject("properties")
            .has("kind"));
  }

  @org.junit.jupiter.api.Tag("design")
  @org.junit.jupiter.api.Tag("inference")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void designAndVillagerRequestsShareOneSerialInferenceQueue() throws Exception {
    AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
    AtomicBoolean designSchema = new AtomicBoolean();
    CountDownLatch done = new CountDownLatch(2);
    ModelBackend backend =
        new ModelBackend() {
          public boolean ready() {
            return true;
          }

          public String status() {
            return "test";
          }

          public void close() {}

          public String complete(String s, String u) {
            throw new AssertionError();
          }

          public String complete(String s, String u, ReasoningMode mode, String schema) {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
              if (mode == ReasoningMode.DESIGN) {
                designSchema.set(schema.equals(Blueprint.SCHEMA));
                return new Gson().toJson(house(3, 3, 5, 5, "north"));
              }
              return "{\"action\":\"rest\"}";
            } finally {
              active.decrementAndGet();
            }
          }
        };
    try (InferenceQueue queue = new InferenceQueue(backend, 4)) {
      assertTrue(
          queue.submit(
              "design:village",
              "architect",
              "terrain",
              ReasoningMode.DESIGN,
              Blueprint.SCHEMA,
              System.currentTimeMillis() + 2000,
              Blueprint::parse,
              b -> {
                assertNotNull(b);
                done.countDown();
              }));
      assertTrue(
          queue.request(
              "villager",
              "inventory",
              Set.of(),
              d -> {
                assertNotNull(d);
                done.countDown();
              }));
      assertTrue(done.await(2, TimeUnit.SECONDS));
      assertEquals(1, peak.get());
      assertTrue(designSchema.get());
      assertTrue(queue.status().contains("design-responses=1"));
    }
  }

  @org.junit.jupiter.api.Tag("navigation")
  @org.junit.jupiter.api.Tag("design")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void terrainMapDistinguishesWaterStructuresReservationsAndUnknownChunks() {
    CoreTest.Flat t =
        new CoreTest.Flat() {
          public boolean available(int x, int z) {
            return x >= -20 && super.available(x, z);
          }
        };
    t.overrides.put(new Pos(0, 64, 0), "WATER");
    t.overrides.put(new Pos(3, 64, 0), "OAK_PLANKS");
    var map = TerrainMap.capture(t, CoreTest.village(), q -> q.x() == 6 && q.z() == 0);
    String rows = map.get("rows_z_then_x").toString();
    assertTrue(rows.contains("?"));
    assertTrue(rows.contains("~"));
    assertTrue(rows.contains("#"));
    assertEquals(3, map.get("cell_size"));
  }
}
