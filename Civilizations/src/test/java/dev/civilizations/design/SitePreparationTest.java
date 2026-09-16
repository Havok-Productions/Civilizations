package dev.civilizations.design;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import dev.civilizations.world.Terrain;
import java.util.*;
import org.junit.jupiter.api.*;

class SitePreparationTest {
  static class Ground implements Terrain {
    final Map<Pos, String> blocks = new HashMap<>();

    public int height(int x, int z) {
      return blocks.keySet().stream()
          .filter(p -> p.x() == x && p.z() == z && !type(p).equals("AIR"))
          .mapToInt(Pos::y)
          .max()
          .orElse(64);
    }

    public boolean available(int x, int z) {
      return Math.abs(x) <= 30 && Math.abs(z) <= 30;
    }

    public String type(Pos p) {
      return !available(p.x(), p.z()) || p.y() < 58
          ? "UNKNOWN"
          : blocks.getOrDefault(p, p.y() < 64 ? "DIRT" : p.y() == 64 ? "GRASS_BLOCK" : "AIR");
    }
  }

  private DesignSite site(Ground t) {
    return new DesignSite(t, new Pos(0, 65, 0), "prepared", p -> false);
  }

  @Test
  @Tag("design")
  @Tag("navigation")
  @Tag("interaction")
  void fillsSupportUnderClutterAndChargesForEveryBlockBeforeClearingHigherWork() {
    Ground t = new Ground();
    Pos gap = new Pos(3, 64, 0);
    t.blocks.put(gap, "LEAF_LITTER");
    DesignSite s = site(t);
    SitePreparation.route(
        s,
        List.of(new Blueprint.Point(2, 0), new Blueprint.Point(3, 0), new Blueprint.Point(4, 0)),
        2);
    // Route can follow the lower ground; an explicit common grade must fill the litter-level gap.
    s = site(t);
    Set<Pos> clear = new HashSet<>(), fill = new HashSet<>();
    SiteFoundations.collect(s, gap, clear, fill);
    SitePreparation.prepare(s, clear, fill);
    assertEquals(2, s.jobs.size());
    assertEquals(Job.Kind.CLEAR, s.jobs.get(0).kind);
    assertEquals(Job.Kind.PLACE, s.jobs.get(1).kind);
    assertEquals(Map.of("COBBLESTONE", 1), TaskSelection.missing(s.jobs.get(1), Map.of()));
    assertTrue(s.jobs.get(1).phase > s.jobs.get(0).phase);
    assertEquals("COBBLESTONE", s.type(gap));
    DesignAccess.verify(s);
  }

  @Test
  @Tag("design")
  void foundationOnExistingMasonryDoesNotAuthorizeItsRemovalOrProtectedConstruction() {
    Ground t = new Ground();
    for (int x = 2; x <= 8; x++)
      for (int z = 2; z <= 8; z++) t.blocks.put(new Pos(x, 64, z), "COBBLESTONE");
    Blueprint house = new Blueprint("house", "Local home", 3, 3, 5, 5, 3, "north", List.of());
    var result =
        new DesignCompiler().compile(house, t, new Pos(0, 65, 0), "house", p -> false, List.of());
    assertTrue(
        result.jobs().stream().noneMatch(j -> j.kind == Job.Kind.CLEAR || j.kind == Job.Kind.MINE));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DesignCompiler()
                .compile(
                    house,
                    t,
                    new Pos(0, 65, 0),
                    "house",
                    p -> p.equals(new Pos(4, 64, 4)),
                    List.of()));
    t.blocks.put(new Pos(4, 65, 4), "OAK_PLANKS");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DesignCompiler()
                .compile(house, t, new Pos(0, 65, 0), "house", p -> false, List.of()));
  }

  @Test
  @Tag("design")
  @Tag("navigation")
  @Tag("interaction")
  void foundationsRejectUnknownWaterAndProtectionAndKeepOtherApproaches() {
    for (String blocker : List.of("UNKNOWN", "WATER", "CHEST")) {
      Ground t = new Ground();
      t.blocks.put(new Pos(3, 64, 0), blocker);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              SiteFoundations.collect(
                  site(t), new Pos(3, 64, 0), new HashSet<>(), new HashSet<>()));
    }
    Ground t = new Ground();
    // First east approach is walled in; west remains reachable.
    t.blocks.put(new Pos(3, 65, 0), "DIRT");
    for (Pos p : List.of(new Pos(5, 65, 0), new Pos(4, 65, 1), new Pos(4, 65, -1))) {
      t.blocks.put(p, "OAK_PLANKS");
      t.blocks.put(p.add(0, 1, 0), "OAK_PLANKS");
    }
    DesignSite s = site(t);
    SitePreparation.prepare(s, Set.of(new Pos(3, 65, 0)), Set.of());
    assertNotEquals(new Pos(4, 65, 0), s.jobs.getFirst().stand);
    DesignAccess.verify(s);
  }

  @Test
  @Tag("design")
  @Tag("navigation")
  @Tag("interaction")
  void elevatedClearanceBuildsReachablePaidStepsAndFailedSidesLeaveNoJobs() {
    Ground t = new Ground();
    Pos leaf = new Pos(3, 70, 0);
    t.blocks.put(leaf, "OAK_LEAVES");
    DesignSite s = site(t);
    s.footprint.add("3,0");
    SitePreparation.prepare(s, Set.of(leaf), Set.of());
    assertTrue(
        s.jobs.stream()
            .anyMatch(j -> j.kind == Job.Kind.PLACE && j.material.equals("COBBLESTONE")));
    assertEquals(Job.Kind.CLEAR, s.jobs.getLast().kind);
    assertTrue(
        s.jobs.stream()
            .filter(j -> j.kind == Job.Kind.PLACE)
            .allMatch(j -> j.phase < s.jobs.getLast().phase));
    DesignAccess.verify(s);
    DesignSite denied = new DesignSite(t, new Pos(0, 65, 0), "denied", p -> true);
    assertFalse(SiteAccessSteps.build(denied, leaf));
    assertTrue(denied.jobs.isEmpty());
  }
}
