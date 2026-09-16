package dev.civilizations.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.Pos;
import dev.coreai.TerrainRuleBook;
import java.lang.reflect.Proxy;
import java.util.*;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.*;

class SnapshotPassabilityTest {
  @Test
  @Tag("design")
  @Tag("navigation")
  @Tag("coreai")
  @Tag("interaction")
  void removableCobwebIsStillAnObstacleAndGrassRemainsPassableInSnapshots() {
    for (Material material : List.of(Material.COBWEB, Material.SHORT_GRASS)) {
      String state = "minecraft:" + material.name().toLowerCase(Locale.ROOT);
      BlockData data =
          (BlockData)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {BlockData.class},
                  (p, m, a) -> m.getName().equals("getAsString") ? state : null);
      ChunkSnapshot chunk =
          (ChunkSnapshot)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {ChunkSnapshot.class},
                  (p, m, a) ->
                      switch (m.getName()) {
                        case "getHighestBlockYAt" -> 65;
                        case "getBlockType" -> ((int) a[1]) == 65 ? material : Material.DIRT;
                        case "getBlockData" -> data;
                        default -> null;
                      });
      var facts =
          new TerrainRuleBook.Facts(
              material.name(),
              state,
              true,
              material == Material.SHORT_GRASS,
              false,
              true,
              false,
              false,
              false);
      var rule = new TerrainRuleBook.Rule(facts, "CLEARABLE", "observed removal", "fixture", 0);
      var terrain =
          new RegionSnapshots.Captured(
              Map.of(0L, chunk),
              0,
              100,
              Map.of(TerrainRuleBook.key(material.name(), state), rule),
              Map.of(),
              1);
      assertEquals(material == Material.SHORT_GRASS, terrain.clear(new Pos(0, 65, 0)));
      assertEquals(material == Material.SHORT_GRASS ? 64 : 65, terrain.groundHeight(0, 0));
    }
  }
}
