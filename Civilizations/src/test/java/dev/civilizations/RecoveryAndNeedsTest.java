package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.ai.*;
import dev.civilizations.core.*;
import dev.civilizations.world.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryAndNeedsTest {
  @TempDir Path tmp;

  @org.junit.jupiter.api.Tag("inference")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void repeatedFailureEscalatesButCooldownPreventsThinkingEveryTick() {
    RecoveryPolicy policy = new RecoveryPolicy(1_000, 120_000);
    policy.failed("No coal");
    assertFalse(policy.needsReasoning(2_000, true));
    policy.failed("No reachable alternate coal");
    assertTrue(policy.needsReasoning(3_000, true));
    policy.submitted(3_000);
    assertFalse(policy.needsReasoning(122_999, true));
    assertTrue(policy.needsReasoning(123_000, true));
    policy.progress(123_000);
    assertFalse(policy.needsReasoning(123_001, true));
  }

  @org.junit.jupiter.api.Tag("inference")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void noProgressEscalatesButNightAndPausedTimeDoNotCount() {
    RecoveryPolicy policy = new RecoveryPolicy(1_000, 120_000);
    assertFalse(policy.needsReasoning(90_999, true));
    assertTrue(policy.needsReasoning(91_000, true));
    assertFalse(policy.needsReasoning(91_000, false));
    policy.pause(700_000);
    assertFalse(policy.needsReasoning(701_000, true));
    assertTrue(policy.needsReasoning(790_000, true));
    policy.failed("No progress while gathering");
    policy.observedStall(790_000);
    assertTrue(
        policy.needsReasoning(790_001, true), "Recording a stall must still request recovery");
    assertFalse(
        policy.stalled(790_001), "A reported stall must not interrupt every subsequent tick");
  }

  @org.junit.jupiter.api.Tag("crafting")
  @Test
  void torchRecipesUseWoodAndFuelAndReuseCraftingLeftovers() {
    assertEquals(
        Map.of("OAK_LOG", 1, "COAL", 1), RecipeCatalog.missing("TORCH", Map.of("COBBLESTONE", 64)));
    assertEquals(Map.of("COAL", 1), RecipeCatalog.missing("TORCH", Map.of("STICK", 3)));
    assertEquals(Map.of("COAL", 1), RecipeCatalog.missing("TORCH", Map.of("OAK_PLANKS", 2)));
    assertEquals(Map.of("TORCH", 1), RecipeCatalog.cost("TORCH", Map.of("TORCH", 3)));
    assertEquals(
        Map.of("TORCH", 3, "STICK", 3, "OAK_PLANKS", 2),
        RecipeCatalog.leftovers("TORCH", Map.of("OAK_LOG", 1, "COAL", 1)));
    assertEquals(
        Map.of("TORCH", 3), RecipeCatalog.leftovers("TORCH", Map.of("STICK", 1, "COAL", 1)));
    assertTrue(RecipeCatalog.leftovers("TORCH", Map.of("TORCH", 1)).isEmpty());
  }

  @org.junit.jupiter.api.Tag("storage")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void incompleteWorkCannotDepositEvenWithManyItemsAndFoodAndSeedsAreRetained() {
    Map<String, Integer> inventory =
        Map.of("COBBLESTONE", 64, "BREAD", 5, "WHEAT_SEEDS", 12, "WHEAT", 9);
    assertTrue(RecipeCatalog.surplus(inventory, false).isEmpty());
    assertEquals(
        Map.of("COBBLESTONE", 64, "BREAD", 2, "WHEAT_SEEDS", 4, "WHEAT", 9),
        RecipeCatalog.surplus(inventory, true));
    assertTrue(RecipeCatalog.surplus(Map.of("BREAD", 2, "WHEAT_SEEDS", 3), true).isEmpty());
    assertTrue(RecipeCatalog.surplus(Map.of("WHEAT", 9), true).isEmpty());
  }

  @org.junit.jupiter.api.Tag("settlements")
  @org.junit.jupiter.api.Tag("diagnostics")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void taskCommitmentsSurviveSwitchingAndRestartAndRequireTheWholeProject() throws Exception {
    Settlement v = CoreTest.village();
    v.enroll("a", 5);
    Job first = CoreTest.job(new Pos(12, 65, 0)), second = CoreTest.job(new Pos(12, 66, 0));
    Job light =
        new Job(
            Job.Kind.PLACE, "lights", new Pos(2, 65, 0), new Pos(3, 65, 0), "TORCH", "AIR", null);
    v.addProject(first.project, List.of(first, second));
    v.addProject("lights", List.of(light));
    v.taskProject("a", first.project);
    assertTrue(v.claim(first.id, "a", 0));
    v.done(first.id, "a");
    assertFalse(v.taskComplete("a"));
    v.taskProject("a", "lights");
    v.claim(light.id, "a", 0);
    v.done(light.id, "a");
    assertFalse(
        v.taskComplete("a"),
        "Switching to a finished task must not free unfinished task materials");
    StateStore store = new StateStore(tmp);
    store.save(List.of(v.snapshot()));
    Settlement restored = store.load(message -> fail(message)).getFirst();
    assertFalse(restored.taskComplete("a"));
    restored.claim(second.id, "other", 1);
    restored.done(second.id, "other");
    assertTrue(restored.taskComplete("a"));
    restored.damaged(first.id);
    assertFalse(restored.taskComplete("a"));
    restored.claim(first.id, "a", 2);
    restored.done(first.id, "a");
    restored.finishTasks("a");
    assertFalse(restored.taskComplete("a"));
    assertEquals("", restored.taskProject("a"));
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void hydratedFarmIsBoundedAndDryOrObstructedSitesAreRejected() {
    FarmPlanner planner = new FarmPlanner();
    CoreTest.Flat t = new CoreTest.Flat();
    Pos c = new Pos(0, 65, 0);
    assertTrue(planner.plan(t, c).isEmpty());
    t.overrides.put(new Pos(0, 64, 0), "WATER");
    List<Job> plots = planner.plan(t, c);
    assertEquals(9, plots.size());
    assertEquals(9, plots.stream().map(j -> j.target).distinct().count());
    assertTrue(
        plots.stream()
            .allMatch(
                j -> j.kind == Job.Kind.FARM && FarmPlanner.hydrated(t, j.target.add(0, -1, 0))));
    for (Job j : plots) t.overrides.put(j.target, "OAK_PLANKS");
    assertTrue(planner.plan(t, c).stream().noneMatch(j -> t.type(j.target).equals("OAK_PLANKS")));
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void grassCoverDoesNotRaiseWallsChestsOrFarmsOffTheGround() {
    CoreTest.Flat grassy =
        new CoreTest.Flat() {
          public int height(int x, int z) {
            return 65;
          }

          public String type(Pos p) {
            return overrides.getOrDefault(p, p.y() == 65 ? "SHORT_GRASS" : super.type(p));
          }
        };
    assertEquals(64, grassy.groundHeight(0, 0));
    Planner planner = new Planner();
    assertFalse(planner.wall(grassy, new Pos(0, 65, 0), 12, 3).isEmpty());
    assertEquals(65, planner.chestSite(grassy, new Pos(0, 65, 0)).y());
    grassy.overrides.put(new Pos(0, 64, 0), "WATER");
    assertFalse(new FarmPlanner().plan(grassy, new Pos(0, 65, 0)).isEmpty());
    assertTrue(
        new FarmPlanner()
            .plan(grassy, new Pos(0, 65, 0)).stream().allMatch(j -> j.target.y() == 65));
  }

  @org.junit.jupiter.api.Tag("storage")
  @org.junit.jupiter.api.Tag("design")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void chestSearchFindsUsableTerrainBeyondOldSixBlockLimit() {
    CoreTest.Flat t = new CoreTest.Flat();
    for (int x = -6; x <= 6; x++)
      for (int z = -6; z <= 6; z++) t.overrides.put(new Pos(x, 65, z), "OAK_PLANKS");
    Pos site = new Planner().chestSite(t, new Pos(0, 65, 0));
    assertNotNull(site);
    assertTrue(Math.max(Math.abs(site.x()), Math.abs(site.z())) > 6);
    assertTrue(Math.max(Math.abs(site.x()), Math.abs(site.z())) <= 16);
  }

  @org.junit.jupiter.api.Tag("storage")
  @org.junit.jupiter.api.Tag("design")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void chestPlanningSkipsAnotherVillagesChestAndReservedBuildSites() {
    CoreTest.Flat terrain = new CoreTest.Flat();
    Pos center = new Pos(0, 65, 0);
    Pos ownedChest = new Pos(1, 65, 0);
    terrain.overrides.put(ownedChest, "CHEST");
    Pos first = new Planner().chestSite(terrain, center);
    var plan =
        new Planner()
            .plan(terrain, center, 12, 3, 6, 6, p -> !p.equals(ownedChest) && !p.equals(first));
    assertNotNull(plan.chest());
    assertNotEquals(ownedChest, plan.chest());
    assertNotEquals(first, plan.chest());
  }

  @org.junit.jupiter.api.Tag("tasks")
  @Test
  void mobSightingsChangeOffersAndExpireWithoutInventingPermanentDanger() {
    Settlement v = CoreTest.village();
    v.enroll("a", 5);
    Job wall = CoreTest.job(new Pos(12, 65, 0));
    Job farm =
        new Job(Job.Kind.FARM, "farm", new Pos(1, 65, 0), new Pos(2, 65, 0), "WHEAT", "AIR", null);
    v.addProject(wall.project, List.of(wall));
    v.addProject("farm", List.of(farm));
    assertEquals(farm.id, TaskSelection.offered(v, "a", v.center(), Map.of(), 1_000).getFirst().id);
    v.needs().threat(1_000);
    assertEquals(wall.id, TaskSelection.offered(v, "a", v.center(), Map.of(), 2_000).getFirst().id);
    assertEquals(
        farm.id, TaskSelection.offered(v, "a", v.center(), Map.of(), 301_001).getFirst().id);
    v.claim(farm.id, "other", 301_001);
    assertFalse(
        TaskSelection.offered(v, "a", v.center(), Map.of(), 301_002).stream()
            .anyMatch(j -> j.id.equals(farm.id)));
  }

  @org.junit.jupiter.api.Tag("inference")
  @Test
  void thinkingBudgetIsSeparateAndSwitchingModelsCanOmitQwenOption() {
    YamlConfiguration config = new YamlConfiguration();
    LocalRuntime.Settings s = LocalRuntime.Settings.read(config);
    var normal = LocalRuntime.requestBody(s, "system", "user", ReasoningMode.NORMAL);
    var recovery = LocalRuntime.requestBody(s, "system", "user", ReasoningMode.RECOVERY);
    assertEquals(180, normal.get("max_tokens").getAsInt());
    assertFalse(
        normal.getAsJsonObject("chat_template_kwargs").get("enable_thinking").getAsBoolean());
    assertEquals(2048, recovery.get("max_tokens").getAsInt());
    assertTrue(
        recovery.getAsJsonObject("chat_template_kwargs").get("enable_thinking").getAsBoolean());
    config.set("ai.thinking", true);
    assertEquals(
        512,
        LocalRuntime.requestBody(LocalRuntime.Settings.read(config), "s", "u", ReasoningMode.NORMAL)
            .get("max_tokens")
            .getAsInt());
    config.set("ai.model.supports-thinking-option", false);
    assertFalse(
        LocalRuntime.requestBody(
                LocalRuntime.Settings.read(config), "s", "u", ReasoningMode.RECOVERY)
            .has("chat_template_kwargs"));
  }

  @org.junit.jupiter.api.Tag("inference")
  @Test
  void reasoningOnlyAndTruncatedOutputNeverBecomeWorldActions() throws Exception {
    assertThrows(
        java.io.IOException.class,
        () ->
            LocalRuntime.finalText(
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{}\"}}]}"));
    assertThrows(
        java.io.IOException.class,
        () ->
            LocalRuntime.finalText(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null,\"reasoning_content\":\"private"
                    + " reasoning\"}}]}"));
    assertEquals(
        "{action}",
        LocalRuntime.finalText(
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{action}\"}}]}"));
  }

  @org.junit.jupiter.api.Tag("inference")
  @Test
  void queueForwardsRecoveryModeAndSkipsExpiredObservations() throws Exception {
    AtomicReference<ReasoningMode> received = new AtomicReference<>();
    AtomicInteger calls = new AtomicInteger();
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
            fail("Mode overload required");
            return "";
          }

          public String complete(String s, String u, ReasoningMode mode) {
            received.set(mode);
            calls.incrementAndGet();
            return "{\"action\":\"rest\"}";
          }
        };
    CountDownLatch done = new CountDownLatch(2);
    try (InferenceQueue queue = new InferenceQueue(backend, 2)) {
      assertTrue(
          queue.request(
              "a",
              "",
              Set.of(),
              ReasoningMode.RECOVERY,
              d -> {
                assertNotNull(d);
                done.countDown();
              }));
      assertTrue(
          queue.request(
              "b",
              "",
              Set.of(),
              ReasoningMode.NORMAL,
              0,
              d -> {
                assertNull(d);
                done.countDown();
              }));
      assertTrue(done.await(2, TimeUnit.SECONDS));
      assertEquals(ReasoningMode.RECOVERY, received.get());
      assertEquals(1, calls.get());
      assertTrue(queue.status().contains("recovery-thinking=1"));
      assertTrue(queue.status().contains("Queued decision expired"));
    }
  }
}
