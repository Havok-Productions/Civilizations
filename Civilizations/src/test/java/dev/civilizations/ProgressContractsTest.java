package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import dev.civilizations.learning.*;
import dev.civilizations.world.*;
import dev.coreai.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ProgressContractsTest {
  @TempDir Path root;

  @Tag("storage")
  @Tag("tasks")
  @Tag("settlements")
  @Tag("interaction")
  @Test
  void projectCachesPersistAndFollowMergeWithoutBecomingCommunityStock() throws Exception {
    var a = CoreTest.village();
    var b = CoreTest.village();
    var bData = b.snapshot();
    bData.world = a.world();
    b = new Settlement(bData);
    var job = CoreTest.job(new Pos(1, 65, 1));
    b.enroll("owner", 5);
    b.addProject(job.project, List.of(job));
    b.taskProject("owner", job.project);
    var cache =
        new Settlement.SupplyCache(
            UUID.randomUUID().toString(), job.project, "owner", new Pos(2, 65, 1));
    b.cache(cache);
    assertFalse(b.mayShareSurplus("owner"));
    var store = new StateStore(root);
    store.save(List.of(a.snapshot(), b.snapshot()));
    var loaded = store.load(message -> fail(message));
    var combined = VillageConnections.combine(loaded);
    assertEquals(1, combined.caches().size());
    String project = combined.caches().getFirst().project();
    assertTrue(combined.jobs().stream().anyMatch(j -> j.project.equals(project)));
    assertEquals(cache.entity(), combined.caches().getFirst().entity());
    assertTrue(combined.stock().isEmpty());
  }

  @Tag("navigation")
  @Tag("tasks")
  @Tag("interaction")
  @Test
  void yieldingBreaksReciprocalRequestsAndExpiresWithoutReleasingWorkClaims() {
    var board = new YieldBoard();
    var spaces = List.of(new Pos(0, 65, 0));
    board.request("a", new YieldBoard.Request("b", "b-task", spaces, 1000), 0);
    board.request("b", new YieldBoard.Request("a", "a-task", spaces, 1000), 0);
    assertNull(board.incoming("a", 1));
    assertEquals("a", board.incoming("b", 1).requester());
    board.cancel("a");
    assertNull(board.incoming("b", 2));
    board.request("b", new YieldBoard.Request("a", "a-task", spaces, 1000), 0);
    assertNull(board.incoming("b", 1000));
  }

  @Tag("design")
  @Tag("tasks")
  @Tag("interaction")
  @Test
  void structuralPropertiesMustMatchButGateUseIsNotConstructionDamage() {
    assertFalse(
        WorkState.propertiesMatch(
            "minecraft:oak_stairs[facing=north,half=bottom]",
            "minecraft:oak_stairs[facing=south,half=bottom,shape=straight]"));
    assertFalse(
        WorkState.propertiesMatch(
            "minecraft:white_bed[facing=north,part=foot]",
            "minecraft:white_bed[facing=north,part=head,occupied=false]"));
    assertTrue(
        WorkState.propertiesMatch(
            "minecraft:oak_fence_gate[facing=north,open=false]",
            "minecraft:oak_fence_gate[facing=north,open=true,powered=true]"));
  }

  @Tag("design")
  @Tag("tasks")
  @Tag("interaction")
  @Test
  void repairSurveyUsesActualBedFacingAndDoesNotTreatAnUnobservedHeadAsMissing() {
    Pos foot = new Pos(0, 65, 0), head = foot.add(1, 0, 0);
    Map<Pos, String> states = new HashMap<>();
    var terrain =
        new CoreTest.Flat() {
          public String blockData(Pos p) {
            return states.get(p);
          }
        };
    String desired = "minecraft:white_bed[facing=east,part=foot]";
    Job bed = new Job(Job.Kind.PLACE, "bed", foot, foot.add(-1, 0, 0), "WHITE_BED", "AIR", desired);
    terrain.overrides.put(foot, "WHITE_BED");
    terrain.overrides.put(head, "WHITE_BED");
    states.put(foot, desired);
    states.put(head, "minecraft:white_bed[facing=east,part=head]");
    assertFalse(WorkState.damaged(bed, terrain));
    terrain.overrides.put(head, "UNKNOWN");
    assertFalse(WorkState.damaged(bed, terrain));
    terrain.overrides.put(head, "AIR");
    assertTrue(WorkState.damaged(bed, terrain));
    terrain.overrides.put(head, "WHITE_BED");
    states.put(head, "minecraft:white_bed[facing=north,part=head]");
    assertTrue(WorkState.damaged(bed, terrain));
  }

  @Tag("crafting")
  @Tag("navigation")
  @Tag("interaction")
  @Test
  void harvestSourcesAndToolProgressionShareAnExecutableCatalog() {
    for (var entry : HarvestCatalog.report().entrySet())
      for (String source : entry.getValue().sources())
        assertTrue(MaterialSources.matches(entry.getKey(), source));
    assertEquals(2, HarvestCatalog.required("RAW_IRON"));
    assertEquals(3, HarvestCatalog.required("DIAMOND"));
    assertEquals("WOODEN_PICKAXE", ToolRecipes.nextTool(3, 0, false));
    assertEquals("STONE_PICKAXE", ToolRecipes.nextTool(3, 1, false));
    assertEquals("IRON_PICKAXE", ToolRecipes.nextTool(3, 2, false));
    assertTrue(HarvestCatalog.capability("SUGAR_CANE").preserveBase());
    assertFalse(HarvestCatalog.supported("NETHER_STAR"));
  }

  @Tag("coreai")
  @Tag("tasks")
  @Tag("interaction")
  @Test
  void learningExcludesNoOpExternalFailureAndOverridesButMeasuresExecutedWork() {
    var choice =
        new CoreAiCoordinator.Choice(
            CoreAiCoordinator.Scope.JOBS,
            "trial:one",
            List.of(new PolicyCase.Option("job", PolicyCase.features(0))));
    var ticket = new CoreAiCoordinator.Ticket("id", "v", "w", choice, "job", 1000, "place:planks");
    assertFalse(
        PolicyEvidence.measure(ticket, true, Map.of("executor_result", "already_satisfied"), 2000)
            .relevant());
    assertFalse(
        PolicyEvidence.measure(ticket, false, Map.of("reason", "region unloaded"), 2000)
            .relevant());
    assertFalse(
        PolicyEvidence.measure(
                ticket,
                true,
                Map.of("interruption", "yielding", "executor_result", "world_changed"),
                2000)
            .relevant());
    assertEquals(
        1000,
        PolicyEvidence.measure(ticket, true, Map.of("executor_result", "world_changed"), 2000)
            .cost());
  }

  @Tag("storage")
  @Tag("tasks")
  @Tag("crafting")
  @Tag("navigation")
  @Tag("interaction")
  @Test
  void capacityRecoveryCoversCraftingHarvestAndRouteDiagnostics() {
    for (String reason :
        List.of(
            "Tool crafting needs inventory space; ingredients retained",
            "No inventory space for farm preparation drops; plot retained",
            "inventory_full_for_obstacle_drops",
            "Inventory full; smelted output retained in furnace",
            "No room for site-clearance drops"))
      assertEquals(WorkFailure.INVENTORY_CAPACITY, WorkFailure.classify(reason), reason);
    assertEquals(
        WorkFailure.OTHER,
        WorkFailure.classify("No dry unreserved crafting-table site within reach"));
  }

  @Tag("storage")
  @Tag("settlements")
  @Tag("interaction")
  @Test
  void remoteCacheMetadataSurvivesSaveAndOldCachesRemainDiscoverable() throws Exception {
    var village = CoreTest.village();
    var cache =
        new Settlement.SupplyCache(
            UUID.randomUUID().toString(), "p", "worker", new Pos(200, 65, 0), "BIRCH_LOG");
    village.cache(cache);
    var store = new StateStore(root);
    store.save(List.of(village.snapshot()));
    var restored = store.load(message -> fail(message)).getFirst().caches().getFirst();
    assertTrue(restored.wanted(Map.of("LOG", 1)));
    assertFalse(restored.wanted(Map.of("COAL", 1)));
    assertEquals(cache, restored);
    assertTrue(
        new Settlement.SupplyCache("legacy", "p", "w", new Pos(0, 65, 0))
            .wanted(Map.of("COAL", 1)));
  }
}
