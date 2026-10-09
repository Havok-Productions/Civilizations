package dev.civilizations.learning;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.civilizations.core.Pos;
import dev.civilizations.navigation.NavigationMap;
import dev.coreai.SkillProgram;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("navigation")
@Tag("coreai")
class VoxelRecoveryTest {
  @TempDir Path root;
  private static final Pos ORIGIN = new Pos(0, 64, 0);

  @Tag("coreai")
  @Tag("navigation")
  @Tag("inference")
  @Test
  void revisedLocalProgramsStayInTheirOriginalTrainingGroupWithTeacherOffline() throws Exception {
    var backend =
        new dev.civilizations.ai.ModelBackend() {
          public boolean ready() {
            return false;
          }

          public String status() {
            return "offline fixture";
          }

          public String complete(String system, String user) {
            throw new AssertionError("Teacher called");
          }

          public void close() {}
        };
    var initial = context(corridor(), Map.of());
    try (var queue = new dev.civilizations.ai.InferenceQueue(backend, 2);
        var experiments =
            new RecoveryExperiments(
                root,
                queue,
                () -> "offline",
                message -> {
                  throw new AssertionError(message);
                },
                true)) {
      var trial = experiments.request("fixture", "worker", initial, System.currentTimeMillis());
      assertNotNull(trial.program.get(5, java.util.concurrent.TimeUnit.SECONDS));
      var changed = corridor();
      changed.put(
          ORIGIN.add(2, 0, 0), cell("SHORT_GRASS", NavigationMap.Kind.AIR, "no_clearance_needed"));
      var fresh = context(changed, Map.of());
      assertNotEquals(VoxelRecoveryPlanner.group(initial), VoxelRecoveryPlanner.group(fresh));
      assertNotNull(
          experiments
              .revise(
                  trial,
                  fresh,
                  Map.of("reason", "new observation"),
                  System.currentTimeMillis(),
                  true)
              .get(5, java.util.concurrent.TimeUnit.SECONDS));
      experiments.finish(trial, true, "unit fixture only", Map.of());
    }
    var line = Files.readAllLines(root.resolve("recovery/physical-outcomes.jsonl")).getFirst();
    assertEquals(
        VoxelRecoveryPlanner.group(initial),
        JsonParser.parseString(line).getAsJsonObject().get("group").getAsString());
  }

  private static NavigationMap.Cell cell(String material, NavigationMap.Kind kind, String removal) {
    return new NavigationMap.Cell(
        material, kind, "minecraft:" + material.toLowerCase(Locale.ROOT), removal);
  }

  private static Map<Pos, NavigationMap.Cell> corridor() {
    Map<Pos, NavigationMap.Cell> cells = new HashMap<>();
    for (int x = -2; x <= 8; x++)
      for (int y = -3; y <= 4; y++)
        for (int z = -2; z <= 2; z++)
          cells.put(
              ORIGIN.add(x, y, z), cell("STONE", NavigationMap.Kind.SOLID, "structure_or_support"));
    for (int x = 0; x <= 6; x++)
      for (int y = -1; y <= 2; y++)
        cells.put(
            ORIGIN.add(x, y, 0),
            y == -1 || y == 2
                ? cell("STONE", NavigationMap.Kind.SOLID, "structure_or_support")
                : cell("AIR", NavigationMap.Kind.AIR, "no_clearance_needed"));
    return cells;
  }

  private static SkillContext context(
      Map<Pos, NavigationMap.Cell> cells, Map<String, Integer> inventory) {
    return SkillContext.create(
        new NavigationMap(ORIGIN, 8, 4, cells),
        ORIGIN,
        ORIGIN.add(6, 0, 0),
        0,
        inventory,
        "blocked");
  }

  @Tag("coreai")
  @Tag("navigation")
  @Test
  void descentMustAccountForTheSweptHeadVolume() {
    var cells = new HashMap<Pos, NavigationMap.Cell>();
    for (int x = -2; x <= 2; x++)
      for (int y = -3; y <= 4; y++)
        for (int z = -3; z <= 2; z++)
          cells.put(
              ORIGIN.add(x, y, z), cell("STONE", NavigationMap.Kind.SOLID, "structure_or_support"));
    for (int z = -2; z <= 0; z++)
      for (int y = -2; y <= 2; y++)
        cells.put(ORIGIN.add(0, y, z), cell("AIR", NavigationMap.Kind.AIR, "no_clearance_needed"));
    cells.put(
        ORIGIN.add(0, -1, 0), cell("STONE", NavigationMap.Kind.SOLID, "structure_or_support"));
    cells.put(
        ORIGIN.add(0, -2, -1), cell("STONE", NavigationMap.Kind.SOLID, "structure_or_support"));
    cells.put(
        ORIGIN.add(0, 1, -1),
        cell(
            "STONE", NavigationMap.Kind.SOLID, "requires_mining_task_and_suitable_tool_not_CLEAR"));
    var map = new NavigationMap(ORIGIN, 3, 4, cells);
    var route =
        dev.civilizations.navigation.TerrainRouteSearch.search(
            map, ORIGIN, ORIGIN.add(0, -1, -1), 0, Set.of(), 0);
    assertFalse(route.reached());
    assertTrue(route.rejected().keySet().stream().anyMatch(k -> k.startsWith("descent_headroom")));
    var context =
        SkillContext.create(
            map, ORIGIN, ORIGIN.add(0, -1, -1), 0, Map.of("WOODEN_PICKAXE", 1), "native rejection");
    var candidate = VoxelRecoveryPlanner.evaluate(context).candidates().getFirst();
    assertEquals("MINE", candidate.strategy());
    assertTrue(
        candidate
            .program()
            .steps()
            .contains(new SkillProgram.Step(SkillProgram.Op.MINE, 0, 1, -1, "STONE")));
    cells.put(ORIGIN.add(1, 1, -1), cell("WATER", NavigationMap.Kind.FLUID, "fluid"));
    var wet =
        SkillContext.create(
            new NavigationMap(ORIGIN, 3, 4, cells),
            ORIGIN,
            ORIGIN.add(0, -1, -1),
            0,
            Map.of("WOODEN_PICKAXE", 1),
            "water nearby");
    assertTrue(
        VoxelRecoveryPlanner.evaluate(wet).candidates().isEmpty(),
        "Do not propose the executor-rejected wet excavation");
  }

  @Tag("coreai")
  @Tag("navigation")
  @Test
  void distantGoalUsesARecordedLocalSegmentThenRequestsFreshVoxels() {
    var map = new NavigationMap(ORIGIN, 8, 4, corridor());
    var context =
        SkillContext.create(map, ORIGIN, ORIGIN.add(30, 0, 0), 0, Map.of(), "beyond local map");
    var program = VoxelRecoveryPlanner.evaluate(context).candidates().getFirst().program();
    assertEquals(SkillProgram.Op.OBSERVE, program.steps().get(program.steps().size() - 2).op());
    assertEquals(SkillProgram.Op.VERIFY, program.steps().getLast().op());
    assertEquals(ORIGIN.add(30, 0, 0), context.goal());
    assertTrue(
        program.steps().stream()
            .filter(s -> s.op() == SkillProgram.Op.WALK)
            .allMatch(s -> s.x() <= 8));
  }

  @Tag("coreai")
  @Tag("navigation")
  @Test
  void naturalBarrierProducesMeasuredClearanceAndPreservesFloor() {
    var cells = corridor();
    cells.put(
        ORIGIN.add(2, 0, 0),
        cell(
            "OAK_LEAVES",
            NavigationMap.Kind.SOFT,
            "CLEAR_within_reach_live_checks_and_retained_drops"));
    cells.put(
        ORIGIN.add(2, 1, 0),
        cell(
            "OAK_LEAVES",
            NavigationMap.Kind.SOFT,
            "CLEAR_within_reach_live_checks_and_retained_drops"));
    var evaluation = VoxelRecoveryPlanner.evaluate(context(cells, Map.of()));
    var plan = evaluation.candidates().getFirst();
    assertEquals("CLEAR", plan.strategy());
    assertEquals(
        2, plan.program().steps().stream().filter(s -> s.op() == SkillProgram.Op.CLEAR).count());
    assertTrue(
        plan.program().steps().stream()
            .filter(s -> s.op() == SkillProgram.Op.CLEAR)
            .allMatch(s -> s.y() >= 0));
    assertEquals(SkillProgram.Op.VERIFY, plan.program().steps().getLast().op());
  }

  @Tag("coreai")
  @Tag("navigation")
  @Test
  void unsupportedGapRequiresRealInventoryAndConnectedSupport() {
    var cells = corridor();
    for (int y = -3; y <= -2; y++)
      cells.put(ORIGIN.add(3, y, 0), cell("AIR", NavigationMap.Kind.AIR, "no_clearance_needed"));
    cells.put(ORIGIN.add(3, -1, 0), cell("AIR", NavigationMap.Kind.AIR, "no_clearance_needed"));
    assertTrue(VoxelRecoveryPlanner.evaluate(context(cells, Map.of())).candidates().isEmpty());
    var candidate =
        VoxelRecoveryPlanner.evaluate(context(cells, Map.of("DIRT", 1))).candidates().getFirst();
    assertEquals("SUPPORT", candidate.strategy());
    var placement =
        candidate.program().steps().stream()
            .filter(s -> s.op() == SkillProgram.Op.PLACE_SUPPORT)
            .findFirst()
            .orElseThrow();
    assertEquals(new SkillProgram.Step(SkillProgram.Op.PLACE_SUPPORT, 3, -1, 0, "DIRT"), placement);
    cells.put(ORIGIN.add(3, -1, 0), cell("LAVA", NavigationMap.Kind.HAZARD, "hazard"));
    assertTrue(
        VoxelRecoveryPlanner.evaluate(context(cells, Map.of("DIRT", 1))).candidates().isEmpty());
  }

  @Tag("coreai")
  @Tag("navigation")
  @Test
  void ordinaryStoneUsesMiningButReservedStoneDoesNot() {
    var cells = corridor();
    for (int y = 0; y <= 1; y++)
      cells.put(
          ORIGIN.add(2, y, 0),
          cell(
              "STONE",
              NavigationMap.Kind.SOLID,
              "requires_mining_task_and_suitable_tool_not_CLEAR"));
    var candidate = VoxelRecoveryPlanner.evaluate(context(cells, Map.of())).candidates().getFirst();
    assertEquals("MINE", candidate.strategy());
    assertEquals(
        2,
        candidate.program().steps().stream().filter(s -> s.op() == SkillProgram.Op.MINE).count());
    for (int y = 0; y <= 1; y++)
      cells.put(
          ORIGIN.add(2, y, 0), cell("STONE", NavigationMap.Kind.SOLID, "protected_or_reserved"));
    assertTrue(VoxelRecoveryPlanner.evaluate(context(cells, Map.of())).candidates().isEmpty());
  }

  @Tag("coreai")
  @Tag("navigation")
  @Test
  void reservationIsNotCollisionAndAirIsNotGround() {
    var cells = corridor();
    Pos goal = ORIGIN.add(6, 0, 0);
    cells.put(goal, cell("AIR", NavigationMap.Kind.AIR, "protected_or_reserved"));
    cells.put(goal.add(0, -1, 0), cell("AIR", NavigationMap.Kind.AIR, "no_clearance_needed"));
    var facts = RecoverySpatialFacts.at(context(cells, Map.of()).map(), ORIGIN, goal);
    assertEquals(true, facts.get("body_empty"));
    assertEquals(false, facts.get("support_present"));
    assertEquals(false, facts.get("clear_body_useful"));
    assertEquals(false, facts.get("standing_admissible_in_snapshot"));
  }

  @Tag("coreai")
  @Tag("navigation")
  @Test
  void replayPreservesStatesAndTranslationGroupingAndRejectsTruncation() {
    var cells = corridor();
    var original = context(cells, Map.of());
    var json = new Gson().toJsonTree(original.map().describe()).getAsJsonObject();
    var decoded = RecoveryReplay.decode(json);
    assertEquals(original.map().cell(ORIGIN), decoded.cell(ORIGIN));
    var shifted = new HashMap<Pos, NavigationMap.Cell>();
    cells.forEach((p, c) -> shifted.put(p.add(128, 0, -200), c));
    Pos from = ORIGIN.add(128, 0, -200);
    var translated =
        SkillContext.create(
            new NavigationMap(from, 8, 4, shifted),
            from,
            from.add(6, 0, 0),
            0,
            Map.of(),
            "different failure");
    assertEquals(VoxelRecoveryPlanner.group(original), VoxelRecoveryPlanner.group(translated));
    json.getAsJsonArray("runs").remove(1);
    assertThrows(IllegalArgumentException.class, () -> RecoveryReplay.decode(json));
  }

  @Tag("coreai")
  @Tag("navigation")
  @Test
  void failedCandidateIsNotRepeatedAfterReloadButChangedTerrainCanRetry() throws Exception {
    var ctx = context(corridor(), Map.of());
    var observation = new HashMap<>(ctx.observation());
    observation.put("verification_goal", "ARRIVAL: reach the original goal with normal movement");
    ctx =
        new SkillContext(
            ctx.key(),
            ctx.map(),
            ctx.origin(),
            ctx.goal(),
            ctx.reach2(),
            ctx.inventory(),
            ctx.failure(),
            Map.copyOf(observation));
    var controller = new RecoveryController(root);
    var candidate = controller.propose("first", ctx);
    assertNotNull(candidate);
    controller.outcome("first", ctx, candidate, false, "native movement stalled");
    assertNull(new RecoveryController(root).propose("second", ctx));
    var changed = corridor();
    changed.put(
        ORIGIN.add(2, 0, 0), cell("SHORT_GRASS", NavigationMap.Kind.AIR, "no_clearance_needed"));
    assertNotNull(new RecoveryController(root).propose("third", context(changed, Map.of())));
  }
}
