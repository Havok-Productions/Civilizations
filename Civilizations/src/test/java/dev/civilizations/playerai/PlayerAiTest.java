package dev.civilizations.playerai;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import dev.civilizations.core.Pos;
import dev.civilizations.world.Terrain;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class PlayerAiTest {
  private static final Pos CAMP = new Pos(10, 64, 10);

  private static final class World implements PlayerAi.Actions {
    final Map<Pos, String> blocks = new HashMap<>();
    PlayerAi.State saved;
    int harvested, harvestCalls, placementCalls, halts;
    boolean denyHarvest, denyPlace, waiting;

    public PlayerAi.Outcome findCamp() {
      return PlayerAi.Outcome.done(null, CAMP);
    }

    public PlayerAi.Outcome gatherWood() {
      harvestCalls++;
      if (denyHarvest) return PlayerAi.Outcome.blocked("Protected tree");
      if (waiting) return PlayerAi.Outcome.waiting("Walking");
      harvested++;
      return PlayerAi.Outcome.done(harvested % 2 == 0 ? "BIRCH_LOG" : "OAK_LOG", null);
    }

    public PlayerAi.Outcome place(StarterCamp.Placement placement, String material) {
      placementCalls++;
      if (denyPlace) return PlayerAi.Outcome.blocked("Protected site");
      assertNull(
          blocks.putIfAbsent(placement.position(), material),
          "A completed block must not be placed twice");
      return PlayerAi.Outcome.done(null, placement.position());
    }

    public void halt() {
      halts++;
    }

    public void checkpoint(PlayerAi.State state) {
      saved = state;
    }
  }

  private PlayerAi fresh(World world) {
    return new PlayerAi(PlayerAi.fresh(PlayerAiProfile.generate(42, null)), world);
  }

  @Test
  @Tag("playerai")
  void completesStarterCampWithExactGatheredMaterials() {
    World world = new World();
    PlayerAi brain = fresh(world);
    for (long now = 0; now < 60_000; now += 1000) brain.tick(now);
    assertEquals(PlayerAi.Stage.COMPLETE, brain.stage());
    assertEquals(6, world.harvested);
    assertEquals(18, world.blocks.size());
    assertEquals(17, world.blocks.values().stream().filter(m -> m.endsWith("_PLANKS")).count());
    assertEquals("CRAFTING_TABLE", world.blocks.get(CAMP.add(1, 0, 1)));
    assertEquals(
        3, brain.snapshot().inventory().values().stream().mapToInt(Integer::intValue).sum());
    assertEquals(18, world.placementCalls, "Completion does not start unlimited construction");
  }

  @Test
  @Tag("playerai")
  void restoresAfterEveryReceiptWithoutDuplicatingItemsOrPlacement() {
    Gson gson = new Gson();
    World world = new World();
    PlayerAi brain = fresh(world);
    UUID identity = brain.snapshot().profile().id();
    for (long now = 0; now < 60_000; now += 1000) {
      brain.tick(now);
      brain =
          new PlayerAi(gson.fromJson(gson.toJson(brain.snapshot()), PlayerAi.State.class), world);
    }
    brain.tick(60_000);
    assertEquals(PlayerAi.Stage.COMPLETE, brain.stage());
    assertEquals(identity, brain.snapshot().profile().id());
    assertEquals(6, world.harvested);
    assertEquals(18, world.placementCalls);
    assertEquals(
        3, brain.snapshot().inventory().values().stream().mapToInt(Integer::intValue).sum());
  }

  @Test
  @Tag("playerai")
  void blockedGatheringBacksOffAndCannotInventResources() {
    World world = new World();
    world.denyHarvest = true;
    PlayerAi brain = fresh(world);
    brain.tick(0);
    brain.tick(1000);
    for (long now = 1100; now < 11_000; now += 100) brain.tick(now);
    assertEquals(PlayerAi.Stage.BLOCKED, brain.stage());
    assertEquals(1, world.harvestCalls);
    assertTrue(brain.snapshot().inventory().isEmpty());
    assertEquals(0, brain.snapshot().built());
    world.denyHarvest = false;
    brain.tick(11_000);
    assertEquals(1, world.harvested);
  }

  @Test
  @Tag("playerai")
  void cancelledPlacementRetainsSuppliesAndProgressAcrossRestart() {
    World world = new World();
    var profile = PlayerAiProfile.generate(4, "Builder");
    var start = new PlayerAi.State(1, profile, CAMP, 0, Map.of("OAK_PLANKS", 24), false);
    PlayerAi brain = new PlayerAi(start, world);
    world.denyPlace = true;
    brain.tick(1000);
    assertEquals(start, brain.snapshot());
    assertEquals(PlayerAi.Stage.BLOCKED, brain.stage());
    brain = new PlayerAi(brain.snapshot(), world);
    world.denyPlace = false;
    brain.tick(2000);
    assertEquals(1, brain.snapshot().built());
    assertEquals(23, brain.snapshot().inventory().get("OAK_PLANKS"));
  }

  @Test
  @Tag("playerai")
  void pauseCancelsWaitingWorkAndSurvivesReload() {
    World world = new World();
    world.waiting = true;
    PlayerAi brain = fresh(world);
    brain.tick(0);
    brain.tick(1000);
    brain.pause(true);
    assertEquals(1, world.halts);
    brain = new PlayerAi(world.saved, world);
    brain.tick(20_000);
    assertEquals(PlayerAi.Stage.PAUSED, brain.stage());
    assertEquals(1, world.harvestCalls);
    brain.pause(false);
    world.waiting = false;
    brain.tick(21_000);
    assertEquals(1, world.harvested);
  }

  @Test
  @Tag("playerai")
  void workbenchCraftingReceiptResumesWithoutPayingTwice() {
    World world = new World();
    var start =
        new PlayerAi.State(
            1,
            PlayerAiProfile.generate(7, null),
            CAMP,
            17,
            Map.of("OAK_PLANKS", 2, "BIRCH_PLANKS", 3),
            false);
    PlayerAi brain = new PlayerAi(start, world);
    brain.tick(0);
    assertEquals(1, brain.snapshot().inventory().get("CRAFTING_TABLE"));
    assertEquals(17, brain.snapshot().built());
    brain = new PlayerAi(brain.snapshot(), world);
    brain.tick(1000);
    assertEquals(18, brain.snapshot().built());
    assertEquals(Map.of("OAK_PLANKS", 1), brain.snapshot().inventory());
    assertEquals(0, world.harvestCalls);
  }

  @Test
  @Tag("playerai")
  void generationIsRepeatableButIdentityIsUnique() {
    var a = PlayerAiProfile.generate(52, null);
    var b = PlayerAiProfile.generate(52, null);
    assertEquals(a.name(), b.name());
    assertEquals(a.seed(), b.seed());
    assertNotEquals(a.id(), b.id());
    assertEquals("Alex", PlayerAiProfile.generate(52, "Alex").name());
    assertThrows(IllegalArgumentException.class, () -> PlayerAiProfile.generate(0, "bad/name"));
  }

  @Test
  @Tag("playerai")
  void refusesUnknownSchemaImpossibleProgressAndInvalidInventory() {
    var profile = PlayerAiProfile.generate(1, null);
    assertThrows(
        IllegalArgumentException.class,
        () -> new PlayerAi.State(2, profile, null, 0, Map.of(), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PlayerAi.State(1, profile, null, 1, Map.of(), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PlayerAi.State(1, profile, CAMP, 19, Map.of(), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PlayerAi.State(1, profile, CAMP, 0, Map.of("DIAMOND", 1), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PlayerAi.State(1, profile, CAMP, 0, Map.of("OAK_LOG", -1), false));
    assertThrows(
        RuntimeException.class, () -> new Gson().fromJson("{\"schema\":2}", PlayerAi.State.class));
  }

  @Test
  @Tag("playerai")
  void durableInventoryDoesNotExposeMutableState() {
    World world = new World();
    PlayerAi brain = fresh(world);
    brain.tick(0);
    brain.tick(1000);
    var saved = brain.snapshot();
    assertThrows(UnsupportedOperationException.class, () -> saved.inventory().put("OAK_LOG", 50));
    brain.tick(2000);
    assertEquals(Map.of("OAK_LOG", 1), saved.inventory());
    assertEquals(Map.of("OAK_PLANKS", 4), brain.snapshot().inventory());
  }

  @Test
  @Tag("playerai")
  @Tag("design")
  @Tag("interaction")
  void orderedCampKeepsEveryWorkPositionAndSightlineClear() {
    Set<Pos> built = new HashSet<>();
    for (var placement : StarterCamp.blueprint(CAMP)) {
      Pos stand = placement.stand(), target = placement.position();
      assertFalse(built.contains(stand));
      assertFalse(built.contains(stand.add(0, 1, 0)));
      double eyeX = stand.x() + .5, eyeY = stand.y() + 1.62, eyeZ = stand.z() + .5;
      double dx = target.x() + .5 - eyeX, dy = target.y() + .5 - eyeY, dz = target.z() + .5 - eyeZ;
      assertTrue(dx * dx + dy * dy + dz * dz < 16, "Every placement must be in reach");
      for (int i = 1; i < 100; i++) {
        double t = i / 100.0;
        Pos alongRay =
            new Pos(
                (int) Math.floor(eyeX + dx * t),
                (int) Math.floor(eyeY + dy * t),
                (int) Math.floor(eyeZ + dz * t));
        assertFalse(
            built.contains(alongRay), "Earlier block obstructs " + target + " from " + stand);
      }
      built.add(target);
    }
    assertEquals(18, built.size());
  }

  @Test
  @Tag("playerai")
  @Tag("design")
  @Tag("interaction")
  void campsiteRejectsUnknownWetOccupiedAndUnevenTerrain() {
    Map<Pos, String> changes = new HashMap<>();
    Terrain terrain =
        new Terrain() {
          public int height(int x, int z) {
            return 63;
          }

          public boolean available(int x, int z) {
            return x >= 0;
          }

          public String type(Pos p) {
            return changes.getOrDefault(p, p.y() < 64 ? "DIRT" : "AIR");
          }
        };
    assertTrue(MannequinActions.flatCamp(terrain, CAMP));
    for (String obstruction : List.of("WATER", "OAK_PLANKS", "OAK_LOG", "UNKNOWN", "SHORT_GRASS")) {
      changes.put(CAMP.add(1, 0, 1), obstruction);
      assertFalse(MannequinActions.flatCamp(terrain, CAMP), obstruction);
    }
    changes.clear();
    changes.put(CAMP.add(0, -1, 0), "AIR");
    assertFalse(MannequinActions.flatCamp(terrain, CAMP));
    assertFalse(MannequinActions.flatCamp(terrain, new Pos(0, 64, 0)));
  }
}
