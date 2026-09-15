package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import dev.civilizations.design.*;
import dev.civilizations.world.*;
import java.util.*;
import org.junit.jupiter.api.*;

class CooperationTest {
  @Test
  @Tag("storage")
  @Tag("tasks")
  @Tag("interaction")
  void deliveriesReserveRecipientsCancelChangedWorkAndExpireAbsentWorkers() {
    DeliveryBoard b = new DeliveryBoard();
    b.publish(
        "builder",
        new DeliveryBoard.Worker(new Pos(10, 65, 0), "wall", Map.of("LOG", 2), Map.of(), 1000));
    b.publish(
        "donor",
        new DeliveryBoard.Worker(
            new Pos(0, 65, 0), "unfinished", Map.of(), Map.of("BIRCH_LOG", 4), 1000));
    b.publish(
        "second",
        new DeliveryBoard.Worker(new Pos(1, 65, 0), "", Map.of(), Map.of("BIRCH_LOG", 4), 1000));
    var d = b.claim("donor", 1001);
    assertEquals(2, d.amount());
    assertNull(b.claim("second", 1001));
    b.received(d, 1);
    assertEquals(1, b.claim("second", 1002).amount());
    b.publish(
        "builder",
        new DeliveryBoard.Worker(new Pos(10, 65, 0), "new-task", Map.of(), Map.of(), 1003));
    assertEquals(0, b.amount(d, 1003));
    assertNull(b.claim("donor", 1003));
    b.publish(
        "builder",
        new DeliveryBoard.Worker(new Pos(10, 65, 0), "next", Map.of("LOG", 2), Map.of(), 1004));
    assertNotNull(b.claim("donor", 1004));
    assertNull(b.claim("donor", 7000));
    b.publish(
        "builder",
        new DeliveryBoard.Worker(
            new Pos(10, 65, 0), "wall-batch", Map.of("COBBLESTONE", 45), Map.of(), 8000));
    b.publish(
        "donor",
        new DeliveryBoard.Worker(
            new Pos(0, 65, 0), "own-task", Map.of(), Map.of("COBBLESTONE", 44), 8000));
    var batch = b.claim("donor", 8001);
    assertEquals(44, batch.amount());
    assertEquals(batch, b.incoming("builder", 8001));
    b.received(batch, 44);
    assertEquals(1, b.worker("builder").need().get("COBBLESTONE"));
  }

  @Test
  @Tag("crafting")
  @Tag("storage")
  @Tag("interaction")
  void courierKeepsHeldIngredientsForImmediateWorkAndSurplusStillWaitsForWholeProject() {
    CraftingBook book =
        new CraftingBook(
            List.of(
                new CraftingBook.Recipe(
                    "planks", "OAK_PLANKS", 4, List.of(List.of("OAK_LOG")), false),
                new CraftingBook.Recipe(
                    "bed",
                    "WHITE_BED",
                    1,
                    List.of(
                        List.of("OAK_PLANKS"),
                        List.of("OAK_PLANKS"),
                        List.of("OAK_PLANKS"),
                        List.of("WHITE_WOOL"),
                        List.of("WHITE_WOOL"),
                        List.of("WHITE_WOOL")),
                    true)));
    assertEquals(
        Map.of("OAK_LOG", 1, "WHITE_WOOL", 2),
        book.reserved("WHITE_BED", Map.of("OAK_LOG", 8, "WHITE_WOOL", 2)));
    assertEquals(
        Map.of("OAK_PLANKS", 1),
        book.reserved("OAK_PLANKS", Map.of("OAK_PLANKS", 64, "OAK_LOG", 4)));
    assertTrue(RecipeCatalog.surplus(Map.of("COBBLESTONE", 64), false).isEmpty());
  }

  @Test
  @Tag("storage")
  @Tag("design")
  @Tag("interaction")
  void storageReusesSharedCapacityAndCreatesOnlyOnePaidExpansionWhenAllStoresAreObservedFull() {
    Settlement v = CoreTest.village();
    Pos a = new Pos(0, 65, 0), b = new Pos(8, 65, 0);
    v.chest(a);
    v.chest(b);
    v.storageCapacity().request(Map.of("COBBLESTONE", 4));
    v.storageCapacity().observe(a, false, 1000);
    assertTrue(StoragePlanning.plan(v, new CoreTest.Flat(), a, 20, 1001).isEmpty());
    v.storageCapacity().observe(b, true, 1000);
    assertTrue(StoragePlanning.plan(v, new CoreTest.Flat(), a, 20, 1001).isEmpty());
    v.storageCapacity().observe(b, false, 1000);
    v.storageCapacity().observe(b, false, Set.of("COBBLESTONE"), 1000);
    assertTrue(StoragePlanning.plan(v, new CoreTest.Flat(), a, 20, 1001).isEmpty());
    v.storageCapacity().observe(b, false, Set.of("SAND"), 1000);
    assertTrue(StoragePlanning.schedule(v, new CoreTest.Flat(), a, 20, 1001));
    assertFalse(StoragePlanning.schedule(v, new CoreTest.Flat(), b, 20, 1001));
    Job job = v.jobs().getLast();
    assertEquals("CHEST", job.material);
    assertEquals(Job.Kind.PLACE, job.kind);
    assertFalse(job.complete);
    assertFalse(v.storageCapacity().needsExpansion(v.chests(), 32000));
  }

  @Test
  @Tag("settlements")
  @Tag("storage")
  @Tag("interaction")
  void workersLeavingDoNotDetachVillageStorageAndBeds() {
    Settlement v = CoreTest.village();
    v.enroll("a", 10);
    v.position("a", new Pos(120, 65, 0));
    Pos chest = new Pos(-10, 65, 0), bed = new Pos(-15, 65, 0);
    v.chest(chest);
    v.beds(List.of(bed));
    assertTrue(
        v.connectionPoints().containsAll(List.of(v.center(), chest, bed, new Pos(120, 65, 0))));
  }

  @Test
  @Tag("design")
  @Tag("navigation")
  @Tag("tasks")
  @Tag("interaction")
  void siteClearsTreeAndMultipleSoilLayersBeforeBuildingAndDoesNotAuthorizeProtectedBlocks() {
    CoreTest.Flat t =
        new CoreTest.Flat() {
          @Override
          public int height(int x, int z) {
            return overrides.keySet().stream()
                .filter(p -> p.x() == x && p.z() == z)
                .mapToInt(Pos::y)
                .max()
                .orElse(64);
          }
        };
    Pos trunk = new Pos(4, 65, 4), dirt = new Pos(6, 65, 4);
    for (int y = 0; y < 3; y++) t.overrides.put(trunk.add(0, y, 0), "OAK_LOG");
    t.overrides.put(trunk.add(0, 3, 0), "OAK_LEAVES");
    t.overrides.put(dirt, "DIRT");
    t.overrides.put(dirt.add(0, 1, 0), "GRASS_BLOCK");
    Blueprint house = DesignTest.house(3, 3, 5, 5, "north");
    var compiled = DesignTest.compile(house, t);
    var clears = compiled.jobs().stream().filter(j -> j.kind == Job.Kind.CLEAR).toList();
    assertEquals(6, clears.size());
    int last = clears.stream().mapToInt(j -> j.phase).max().orElseThrow();
    assertTrue(
        compiled.jobs().stream()
            .filter(j -> j.kind == Job.Kind.PLACE)
            .allMatch(j -> j.phase > last));
    Settlement v = CoreTest.village();
    v.enroll("worker", 10);
    v.addProject(compiled.jobs().getFirst().project, compiled.jobs());
    Job build =
        compiled.jobs().stream().filter(j -> j.kind == Job.Kind.PLACE).findFirst().orElseThrow();
    assertFalse(v.available(build.id, "worker", 1000));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DesignCompiler()
                .compile(
                    house, t, new Pos(0, 65, 0), "design-protected", trunk::equals, List.of()));
    t.overrides.put(trunk, "OAK_PLANKS");
    assertThrows(IllegalArgumentException.class, () -> DesignTest.compile(house, t));
  }

  @Test
  @Tag("navigation")
  @Tag("design")
  @Tag("interaction")
  void soilBesideWallCanBeClearedButItsFoundationCannot() {
    CoreTest.Flat t = new CoreTest.Flat();
    Pos dirt = new Pos(3, 65, 0);
    t.overrides.put(dirt, "DIRT");
    t.overrides.put(dirt.add(1, 0, 0), "STONE_BRICKS");
    assertFalse(NavigationTerrain.architectureNear(t, dirt, Set.of(dirt.add(1, 0, 0))));
    t.overrides.put(dirt.add(0, 1, 0), "STONE_BRICKS");
    assertTrue(NavigationTerrain.architectureNear(t, dirt, Set.of()));
  }
}
