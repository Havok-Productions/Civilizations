package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

class StorageExchangeTest {
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
