package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import dev.civilizations.design.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrerequisiteTest {
  @TempDir Path tmp;

  @org.junit.jupiter.api.Tag("storage")
  @org.junit.jupiter.api.Tag("crafting")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void miningTiersAndSurplusRetainToolsForRealWork() {
    assertEquals(3, ToolRecipes.tier(Map.of("IRON_PICKAXE", 1)));
    assertEquals(1, ToolRecipes.tier(Map.of("WOODEN_PICKAXE", 1)));
    assertEquals(2, ToolRecipes.required("DEEPSLATE_IRON_ORE"));
    assertEquals(1, ToolRecipes.required("DEEPSLATE_COAL_ORE"));
    assertEquals(0, ToolRecipes.required("DIRT"));
    assertEquals(
        Map.of("COAL", 4),
        RecipeCatalog.surplus(
            Map.of("WOODEN_PICKAXE", 1, "STONE_PICKAXE", 1, "CRAFTING_TABLE", 1, "COAL", 4), true));
    assertTrue(RecipeCatalog.surplus(Map.of("COAL", 4, "WOODEN_PICKAXE", 1), false).isEmpty());
  }

  @org.junit.jupiter.api.Tag("tasks")
  @Test
  void failedCoalSupplyIsSharedAndMineRemainsAvailableInsteadOfRepeatingEveryTorch() {
    Settlement v = CoreTest.village();
    v.enroll("a", 5);
    v.enroll("b", 5);
    Job light =
        new Job(
            Job.Kind.PLACE, "lights", new Pos(1, 65, 0), new Pos(2, 65, 0), "TORCH", "AIR", null);
    Job mine =
        new Job(Job.Kind.MINE, "mine", new Pos(12, 64, 0), new Pos(11, 65, 0), "", "STONE", null);
    v.addProject("lights", List.of(light));
    v.addProject("mine", List.of(mine));
    v.knowledge().need("COAL", "lights", 1000);
    v.knowledge().block("resource:COAL", "No safe source", 1000, 60_000);
    for (String worker : List.of("a", "b")) {
      var offered = TaskSelection.offered(v, worker, new Pos(0, 65, 0), Map.of(), 1001);
      assertEquals(List.of(mine.id), offered.stream().map(j -> j.id).toList());
    }
    assertEquals(2, TaskSelection.offered(v, "a", new Pos(0, 65, 0), Map.of(), 61_001).size());
    assertEquals(1, v.supplyNeeds(1001).size());
    v.claim(light.id, "a", 1002);
    v.done(light.id, "a");
    assertTrue(v.supplyNeeds(1003).isEmpty());
  }

  @org.junit.jupiter.api.Tag("settlements")
  @org.junit.jupiter.api.Tag("diagnostics")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void factsProgressAndStationSurviveRestartButOldGeneratedExplanationsAreNotFacts()
      throws Exception {
    Settlement v = CoreTest.village();
    v.enroll("a", 5);
    v.remember("a", "Decision: rest — recovering imaginary energy", false);
    v.remember("a", "Gathered OAK_LOG at 1,65,0", true);
    v.suggestion("a", "work: obtain fuel for lights");
    long now = System.currentTimeMillis();
    v.knowledge().block("route:1,65,0", "No reachable approach", now, 30_000);
    v.knowledge().progress("a", "lights", "Craft wooden pickaxe", "", "Gathered OAK_LOG", now);
    v.craftingTable(new Pos(2, 65, 0));
    StateStore store = new StateStore(tmp);
    store.save(List.of(v.snapshot()));
    Settlement restored = store.load(message -> fail(message)).getFirst();
    assertEquals(1, restored.memories("a").size());
    assertTrue(restored.memories("a").getFirst().result().startsWith("Gathered"));
    assertTrue(restored.knowledge().blocked("route:1,65,0", now + 1));
    assertEquals("Craft wooden pickaxe", restored.knowledge().worker("a").step());
    assertEquals(v.craftingTable(), restored.craftingTable());
    assertTrue(restored.gatherProtected(restored.craftingTable()));
    assertTrue(restored.suggestion("a").contains("obtain fuel"));
  }

  @org.junit.jupiter.api.Tag("tasks")
  @Test
  void proposedTaskChainIncludesToolsCoalExplorationVerificationAndWholeProjectCompletion() {
    var job =
        new Job(
            Job.Kind.PLACE, "lights", new Pos(1, 65, 0), new Pos(2, 65, 0), "TORCH", "AIR", null);
    String plan = TaskPlan.describe(job, Map.of()).toString();
    assertTrue(plan.contains("wooden pickaxe"));
    assertTrue(plan.contains("validated mine"));
    assertTrue(plan.contains("does not guarantee coal"));
    assertTrue(plan.contains("Finish the committed project"));
    assertFalse(
        TaskPlan.describe(job, Map.of("WOODEN_PICKAXE", 1))
            .toString()
            .contains("craft the pickaxe"));
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void candidateMineExamplesAreActuallyCompilableAndReportCoalWithoutGuaranteeingUnseenOre() {
    CoreTest.Flat terrain = new CoreTest.Flat();
    Settlement v = CoreTest.village();
    var candidates = SiteObservations.candidates(terrain, v, p -> false, Set.of("mine"));
    assertFalse(candidates.isEmpty());
    assertTrue(candidates.size() <= 3);
    for (var candidate : candidates) {
      var b = (Blueprint) candidate.get("blueprint");
      var compiled =
          new DesignCompiler().compile(b, terrain, v.center(), "review", p -> false, List.of());
      assertEquals(compiled.jobs().size(), candidate.get("actions"));
      assertEquals(0L, candidate.get("coal_blocks_in_route"));
    }
    assertTrue(
        SiteObservations.candidates(terrain, v, p -> true, Set.of("mine", "house", "farm"))
            .isEmpty());
  }
}
