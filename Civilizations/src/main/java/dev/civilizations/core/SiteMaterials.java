package dev.civilizations.core;

import java.util.Set;

/** Natural ground and clutter. Trees require additional observed evidence before clearing. */
public final class SiteMaterials {
  private SiteMaterials() {}

  public static boolean vegetation(String type) {
    return Set.of(
            "SHORT_GRASS",
            "LEAF_LITTER",
            "MOSS_CARPET",
            "PALE_MOSS_CARPET",
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
    return vegetation(type) || earthwork(type);
  }

  /** Explicit site grading may remove these, after checking overhead blocks and protection. */
  public static boolean earthwork(String type) {
    return soil(type) || Set.of("DIRT_PATH", "GRAVEL").contains(type);
  }

  public static boolean soil(String type) {
    return Set.of("DIRT", "GRASS_BLOCK", "COARSE_DIRT", "PODZOL", "ROOTED_DIRT", "MYCELIUM")
        .contains(type);
  }
}
