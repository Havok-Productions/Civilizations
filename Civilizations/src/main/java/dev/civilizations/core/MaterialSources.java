package dev.civilizations.core;

import java.util.*;

/**
 * Resource hypotheses guide observation; only observed matching blocks become gathering targets.
 */
public final class MaterialSources {
  private MaterialSources() {}

  public static boolean matches(String resource, String block) {
    return switch (resource) {
      case "LOG" -> block.endsWith("_LOG") && !block.startsWith("STRIPPED_");
      case "COAL" -> block.equals("COAL_ORE") || block.equals("DEEPSLATE_COAL_ORE");
      case "COBBLESTONE" -> block.equals("STONE");
      case "WHEAT_SEEDS" -> block.equals("SHORT_GRASS") || block.equals("TALL_GRASS");
      case "SAND", "RED_SAND" -> block.equals(resource);
      default -> resource.endsWith("_LOG") && block.equals(resource);
    };
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
