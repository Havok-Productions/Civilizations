package dev.civilizations.core;

import java.util.Set;

/** A small explicit terrain-preparation vocabulary; buildings, trees and crops are not spoil. */
public final class SiteMaterials {
  private SiteMaterials() {}

  public static boolean vegetation(String type) {
    return Set.of(
            "SHORT_GRASS",
            "LEAF_LITTER",
            "PINK_PETALS",
            "WILDFLOWERS",
            "TALL_GRASS",
            "FERN",
            "LARGE_FERN",
            "SNOW",
            "DANDELION",
            "POPPY",
            "BLUE_ORCHID",
            "ALLIUM",
            "AZURE_BLUET",
            "RED_TULIP",
            "ORANGE_TULIP",
            "WHITE_TULIP",
            "PINK_TULIP",
            "OXEYE_DAISY",
            "CORNFLOWER",
            "LILY_OF_THE_VALLEY",
            "SUNFLOWER",
            "LILAC",
            "ROSE_BUSH",
            "PEONY",
            "DEAD_BUSH",
            "BUSH",
            "FIREFLY_BUSH",
            "SHORT_DRY_GRASS",
            "TALL_DRY_GRASS")
        .contains(type);
  }

  public static boolean clearable(String type) {
    return vegetation(type) || Set.of("DIRT", "GRASS_BLOCK").contains(type);
  }
}
