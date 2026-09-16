package dev.civilizations.design;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import dev.civilizations.world.Terrain;
import java.util.*;
import org.junit.jupiter.api.*;

class DesignAccessTest {
  private static class Field implements Terrain {
    final Map<Pos, String> blocks = new HashMap<>();
    int halfWidth = 100;

    public int height(int x, int z) {
      return 64;
    }

    public boolean available(int x, int z) {
      return Math.abs(x) <= 100 && Math.abs(z) <= halfWidth;
    }

    public String type(Pos p) {
      return blocks.getOrDefault(p, p.y() > 64 ? "AIR" : p.y() == 64 ? "GRASS_BLOCK" : "STONE");
    }
  }

  private static DesignSite site(Field terrain, Pos... stands) {
    var site = new DesignSite(terrain, new Pos(0, 65, 0), "test", p -> false);
    for (Pos stand : stands)
      site.jobs.add(
          new Job(Job.Kind.PLACE, "test", stand.add(0, 0, 1), stand, "TORCH", "AIR", null));
    return site;
  }

  @Tag("design")
  @Tag("navigation")
  @Tag("interaction")
  @Test
  void extraOpenGroundCannotInvalidateAlreadyReachableWork() {
    var terrain = new Field();
    var site = site(terrain, new Pos(80, 65, 0));
    terrain.halfWidth = 2;
    assertDoesNotThrow(() -> DesignAccess.verify(site));
    terrain.halfWidth = 100;
    assertDoesNotThrow(() -> DesignAccess.verify(site));
  }

  @Tag("design")
  @Tag("navigation")
  @Tag("interaction")
  @Test
  void reachingFirstStandDoesNotAcceptASecondDisconnectedStand() {
    var terrain = new Field();
    for (int z = -100; z <= 100; z++) terrain.blocks.put(new Pos(5, 64, z), "WATER");
    var site = site(terrain, new Pos(2, 65, 0), new Pos(9, 65, 0));
    var error = assertThrows(IllegalArgumentException.class, () -> DesignAccess.verify(site));
    assertTrue(error.getMessage().contains("disconnected"));
  }

  @Tag("design")
  @Tag("navigation")
  @Tag("interaction")
  @Test
  void clearingUsesAReachableNextStageWithoutRequiringBlockedLaterStages() {
    var terrain = new Field();
    var site = site(terrain);
    terrain.blocks.put(new Pos(80, 65, 0), "OAK_LOG");
    terrain.blocks.put(new Pos(80, 66, 0), "OAK_LOG");
    terrain.blocks.put(new Pos(80, 67, 0), "OAK_LEAVES");
    assertDoesNotThrow(() -> SitePreparation.route(site, List.of(new Blueprint.Point(80, 0)), 3));
    assertEquals(3, site.jobs.size());
    assertTrue(site.jobs.stream().allMatch(j -> j.kind == Job.Kind.CLEAR));
    assertEquals(3, site.prepared.size());
    assertDoesNotThrow(() -> DesignAccess.verify(site));
    var reached =
        DesignAccess.reachable(
            site, site.prepared, List.of(new Pos(79, 65, 0), new Pos(95, 90, 0)), true);
    assertTrue(reached.contains(DesignAccess.key(new Pos(79, 65, 0))));
  }
}
