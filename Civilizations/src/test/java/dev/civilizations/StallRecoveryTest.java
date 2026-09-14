package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import dev.civilizations.design.*;
import dev.civilizations.world.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class StallRecoveryTest {
  @org.junit.jupiter.api.Tag("inference")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void villageRecoveryIsSharedButIndependentVillagesCanThink() {
    var gate = new VillageThinkingBudget();
    assertTrue(gate.take("village", 1000, 120000));
    assertFalse(gate.take("village", 1001, 120000));
    assertTrue(gate.take("other", 1001, 120000));
    assertTrue(gate.take("village", 121000, 120000));
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void surveysOfferWallsAndPathsAndProtectAnInhabitedLandmark() {
    Settlement v = CoreTest.village();
    v.beds(List.of(new Pos(10, 65, 0)));
    v.chest(new Pos(4, 65, 3));
    var candidates =
        SiteObservations.candidates(new CoreTest.Flat(), v, p -> false, Set.of("wall", "path"));
    assertTrue(
        candidates.stream().anyMatch(e -> ((Blueprint) e.get("blueprint")).kind().equals("path")));
    var walls =
        candidates.stream()
            .map(e -> (Blueprint) e.get("blueprint"))
            .filter(b -> b.kind().equals("wall"))
            .toList();
    assertFalse(walls.isEmpty());
    assertTrue(
        walls.stream()
            .allMatch(
                b ->
                    DesignCompiler.insideWall(b, v.center(), v.beds().getFirst())
                        || DesignCompiler.insideWall(b, v.center(), v.chest())));
  }

  @org.junit.jupiter.api.Tag("design")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void resourceCapKeepsNearbyGrassRatherThanNorthwestScanOrder() {
    Terrain grass =
        new CoreTest.Flat() {
          public String type(Pos p) {
            return p.y() == 65 ? "SHORT_GRASS" : super.type(p);
          }
        };
    Pos center = new Pos(0, 65, 0);
    var plan = new Planner().plan(grass, center, 12, 3, 6, 3, p -> true, false);
    assertEquals(128, plan.seeds().size());
    assertEquals(center, plan.seeds().getFirst());
    assertTrue(plan.seeds().stream().allMatch(p -> p.horizontal2(center) < 100));
  }

  @org.junit.jupiter.api.Tag("navigation")
  @org.junit.jupiter.api.Tag("design")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void walkingAccessUsesFloorUnderRoofInsteadOfHeightmapTop() {
    Terrain roofed =
        new CoreTest.Flat() {
          public int height(int x, int z) {
            return Math.abs(x) <= 5 && Math.abs(z) <= 5 ? 68 : 64;
          }

          public String type(Pos p) {
            return Math.abs(p.x()) <= 5 && Math.abs(p.z()) <= 5 && p.y() == 68
                ? "OAK_PLANKS"
                : super.type(p);
          }
        };
    assertFalse(
        DesignTest.compile(DesignTest.house(10, 3, 5, 5, "north"), roofed).jobs().isEmpty());
  }

  static class Graded extends CoreTest.Flat {
    final Map<Pos, String> blocks = new HashMap<>();

    Graded(String mound) {
      blocks.put(new Pos(5, 65, 5), mound);
    }

    public int height(int x, int z) {
      return blocks.containsKey(new Pos(x, 65, z)) ? 65 : 64;
    }

    public String type(Pos p) {
      return blocks.getOrDefault(p, super.type(p));
    }
  }

  @org.junit.jupiter.api.Tag("design")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void oneLayerSoilCreatesClearanceBeforeConstructionWithNoImaginaryMaterials() {
    var result = DesignTest.compile(DesignTest.house(3, 3, 5, 5, "north"), new Graded("DIRT"));
    Job clear = result.jobs().getFirst();
    assertEquals(Job.Kind.CLEAR, clear.kind);
    assertEquals(new Pos(5, 65, 5), clear.target);
    assertEquals("DIRT", clear.expected);
    assertTrue(
        result.jobs().stream()
            .anyMatch(j -> j.kind == Job.Kind.PLACE && j.target.equals(clear.target)));
    assertTrue(TaskSelection.missing(clear, Map.of()).isEmpty());
    assertTrue(WorkerPlan.needed(new CraftingBook(List.of()), clear, Map.of(), false).isEmpty());
  }

  @org.junit.jupiter.api.Tag("design")
  @Test
  void gradingRejectsBuildingsWaterAndProtectedSoil() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DesignTest.compile(DesignTest.house(3, 3, 5, 5, "north"), new Graded("OAK_PLANKS")));
    assertThrows(
        IllegalArgumentException.class,
        () -> DesignTest.compile(DesignTest.house(3, 3, 5, 5, "north"), new Graded("WATER")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DesignCompiler()
                .compile(
                    DesignTest.house(3, 3, 5, 5, "north"),
                    new Graded("DIRT"),
                    new Pos(0, 65, 0),
                    "design-test",
                    p -> p.equals(new Pos(5, 65, 5)),
                    List.of()));
    assertTrue(SiteMaterials.vegetation("OXEYE_DAISY"));
    assertFalse(SiteMaterials.clearable("OAK_LOG"));
  }

  @org.junit.jupiter.api.Tag("design")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void repairsLearnObservedBlocksRetainOriginalAndDoNotInventOldHoles() {
    Settlement v = CoreTest.village();
    v.beds(List.of(new Pos(0, 65, 0)));
    Graded t = new Graded("OAK_PLANKS");
    Pos original = new Pos(5, 65, 5);
    assertEquals(1, VillageRepairs.survey(v, t).get("newly_recorded"));
    assertTrue(v.jobs().isEmpty());
    assertTrue(v.protectedPos(original));
    t.blocks.remove(original);
    assertEquals(1, VillageRepairs.survey(v, t).get("repair_jobs_added"));
    Job j = v.jobs().getFirst();
    assertEquals("OAK_PLANKS", j.material);
    assertTrue(j.everBuilt);
    assertEquals(original, j.target);
    assertEquals(1, v.jobs().size());
    assertEquals(0, VillageRepairs.survey(v, t).get("repair_jobs_added"));
    Settlement reloaded = new Settlement(v.snapshot());
    assertEquals(v.repairBlocks(), reloaded.repairBlocks());
    assertTrue(
        VillageRepairs.survey(CoreTest.village(), new CoreTest.Flat()).get("repair_jobs_added")
            == 0);
  }

  @org.junit.jupiter.api.Tag("design")
  @org.junit.jupiter.api.Tag("inference")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void sampledOptionsKeepFarmGeometryInsteadOfRepeatedlyInventingCoordinates() {
    Blueprint b = new Blueprint("farm", "Need food", 2, 4, 1, 1, 0, "north", List.of());
    String schema =
        SupplyDesignSchema.surveyed(List.of(Map.of("blueprint", b, "coal_blocks_in_route", 0)));
    var properties =
        com.google.gson.JsonParser.parseString(schema)
            .getAsJsonObject()
            .getAsJsonObject("properties");
    assertEquals(2, properties.getAsJsonObject("x").get("const").getAsInt());
    assertEquals("farm", properties.getAsJsonObject("kind").get("const").getAsString());
  }
}
