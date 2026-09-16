package dev.civilizations.core;

import java.util.*;

/**
 * Resource hypotheses guide observation; only observed matching blocks become gathering targets.
 */
public final class MaterialSources {
  private MaterialSources() {}

  public static boolean matches(String resource, String block) {
    return HarvestCatalog.matches(resource, block);
  }

  public static Map<String, String> knowledge() {
    return Map.of(
        "GLASS",
            "Smelt observed SAND or RED_SAND in a furnace with real fuel; glass is not a natural"
                + " river deposit.",
        "SAND",
            "Look for exposed sand on beaches, riverbanks and deserts. Prefer dry top layers that"
                + " will not release water or falling sand.",
        "COAL",
            "Mine observed coal ore with a pickaxe. Survey exposed hillsides and caves; an"
                + " unexplored mine is not guaranteed coal.",
        "CHARCOAL",
            "Smelt a log with a separate fuel item; charcoal can fuel a furnace and compatible"
                + " torch recipes.",
        "LOG",
            "Look for rooted natural tree trunks with leaves; buildings made from logs are not"
                + " trees.",
        "COBBLESTONE",
            "Mine exposed stone with a pickaxe; craft a furnace from cobblestone at a crafting"
                + " table.");
  }
}
