package dev.civilizations.world;

import dev.civilizations.core.Pos;
import java.util.Set;

public interface Terrain {
  int height(int x, int z);

  String type(Pos p);

  default String blockData(Pos p) {
    return null;
  }

  boolean available(int x, int z);

  default java.util.Map<String, ?> observationReport() {
    return java.util.Map.of();
  }

  default boolean matureWheat(Pos p) {
    return false;
  }

  /** Ignore shallow grass/flowers/snow cover, without excavating through structures. */
  default int groundHeight(int x, int z) {
    int y = height(x, z);
    for (int i = 0; i < 4 && clear(new Pos(x, y, z)); i++) y--;
    return y;
  }

  default boolean bedFoot(Pos p) {
    return true;
  }

  default boolean clear(Pos p) {
    return dev.civilizations.core.SiteMaterials.vegetation(type(p))
        || Set.of(
                "AIR",
                "CAVE_AIR",
                "VOID_AIR",
                "SHORT_GRASS",
                "TALL_GRASS",
                "FERN",
                "LARGE_FERN",
                "SNOW",
                "DANDELION",
                "POPPY")
            .contains(type(p));
  }

  default boolean natural(Pos p) {
    return dev.civilizations.core.SiteMaterials.soil(type(p))
        || dev.civilizations.core.HarvestCatalog.mineral(type(p))
        || Set.of(
                "STONE",
                "DEEPSLATE",
                "GRANITE",
                "DIORITE",
                "ANDESITE",
                "DIRT",
                "GRASS_BLOCK",
                "DIRT_PATH",
                "GRAVEL",
                "COAL_ORE",
                "DEEPSLATE_COAL_ORE",
                "IRON_ORE",
                "DEEPSLATE_IRON_ORE",
                "COPPER_ORE",
                "DEEPSLATE_COPPER_ORE")
            .contains(type(p));
  }

  default boolean fluid(Pos p) {
    return Set.of("WATER", "LAVA", "KELP", "KELP_PLANT", "SEAGRASS", "TALL_SEAGRASS")
        .contains(type(p));
  }

  default boolean dry(Pos p) {
    for (int x = -1; x <= 1; x++)
      for (int y = -1; y <= 1; y++)
        for (int z = -1; z <= 1; z++)
          if (fluid(p.add(x, y, z)) || type(p.add(x, y, z)).equals("UNKNOWN")) return false;
    return true;
  }
}
