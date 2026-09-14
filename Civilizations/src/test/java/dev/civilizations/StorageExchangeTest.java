package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

class StorageExchangeTest {
  @Tag("storage")
  @Tag("crafting")
  @Tag("tasks")
  @Tag("interaction")
  @Test
  void collectsFinishedProjectMaterialsWithoutTakingOthersClaimedBlocks() {
    Settlement.Data d = new Settlement.Data();
    d.world = UUID.randomUUID().toString();
    d.center = new Pos(0, 1, 0);
    d.agents.put("worker", new Settlement.Agent("worker", "builder"));
    for (int x = 0; x < 7; x++)
      d.jobs.add(
          new Job(
              Job.Kind.PLACE,
              "wall",
              new Pos(x, 1, 0),
              new Pos(x, 1, 1),
              "COBBLESTONE",
              "AIR",
              null));
    d.jobs.get(0).complete = true;
    d.jobs.get(1).owner = "other";
    d.jobs.get(1).leaseUntil = 2000;
    Settlement village = new Settlement(d);
    village.taskProject("worker", "wall");
    int demand = village.placementDemand("worker", "COBBLESTONE", 1000);
    assertEquals(5, demand);
    CraftingBook book = new CraftingBook(List.of());
    assertEquals(
        Map.of("COBBLESTONE", 3),
        book.withdrawal(
            "COBBLESTONE", Map.of("COBBLESTONE", 2), Map.of("COBBLESTONE", 100), false, demand));
    assertEquals(
        Map.of("COBBLESTONE", 2),
        book.withdrawal("COBBLESTONE", Map.of(), Map.of("COBBLESTONE", 2), false, demand));
    assertTrue(
        book.withdrawal(
                "COBBLESTONE", Map.of("COBBLESTONE", 5), Map.of("COBBLESTONE", 100), false, demand)
            .isEmpty());
    assertEquals(1, village.placementDemand("worker", "STONE_PICKAXE", 1000));
  }

  @org.junit.jupiter.api.Tag("storage")
  @org.junit.jupiter.api.Tag("crafting")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void encountersCountActualReceiptsAcrossDonorsAndWoodSpecies() {
    assertEquals(
        Map.of(),
        SupplyRequests.remaining(Map.of("COAL", 1), Map.of("COAL", 2), Map.of("COAL", 3)));
    assertEquals(
        Map.of("LOG", 1),
        SupplyRequests.remaining(Map.of("LOG", 2), Map.of("BIRCH_LOG", 1), Map.of("BIRCH_LOG", 2)));
    assertEquals(
        Map.of("COAL", 1),
        SupplyRequests.remaining(Map.of("COAL", 1), Map.of(), Map.of("DIRT", 4)));
  }

  @org.junit.jupiter.api.Tag("storage")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void donorCannotShareWithHiddenUnfinishedCommitments() {
    Settlement.Data d = new Settlement.Data();
    d.world = UUID.randomUUID().toString();
    d.center = new Pos(0, 1, 0);
    Settlement.Agent a = new Settlement.Agent("worker", "gatherer");
    a.committedProjects.add("unfinished");
    d.projects.add("unfinished");
    d.agents.put(a.id, a);
    d.jobs.add(
        new Job(
            Job.Kind.PLACE,
            "unfinished",
            new Pos(1, 1, 1),
            new Pos(0, 1, 1),
            "TORCH",
            "AIR",
            null));
    Settlement village = new Settlement(d);
    assertFalse(village.mayShareSurplus("worker"));
    assertTrue(village.claim(d.jobs.getFirst().id, "worker", System.currentTimeMillis()));
    village.done(d.jobs.getFirst().id, "worker");
    assertTrue(village.mayShareSurplus("worker"));
  }
}
