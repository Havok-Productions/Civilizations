package dev.civilizations.core;

import java.util.*;

/**
 * Executable source capabilities shared by observation, prerequisites and the gathering executor.
 */
public final class HarvestCatalog {
  public record Capability(Set<String> sources, int pickaxeTier, boolean preserveBase) {}

  private static final Map<String, Capability> CAPABILITIES = new LinkedHashMap<>();
  private static final Set<String> MINERALS = new HashSet<>();

  static {
    add("COBBLESTONE", 1, "STONE");
    add("COBBLED_DEEPSLATE", 1, "DEEPSLATE");
    add("COAL", 1, "COAL_ORE", "DEEPSLATE_COAL_ORE");
    add("RAW_IRON", 2, "IRON_ORE", "DEEPSLATE_IRON_ORE");
    add("RAW_COPPER", 2, "COPPER_ORE", "DEEPSLATE_COPPER_ORE");
    add("RAW_GOLD", 3, "GOLD_ORE", "DEEPSLATE_GOLD_ORE");
    add("DIAMOND", 3, "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE");
    add("EMERALD", 3, "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE");
    add("REDSTONE", 3, "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE");
    add("LAPIS_LAZULI", 2, "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE");
    add("QUARTZ", 1, "NETHER_QUARTZ_ORE");
    add("CLAY_BALL", 0, "CLAY");
    add("FLINT", 0, "GRAVEL");
    add("DIRT", 0, "DIRT", "GRASS_BLOCK", "COARSE_DIRT", "ROOTED_DIRT");
    add("WHEAT_SEEDS", 0, "SHORT_GRASS", "TALL_GRASS");
    add("MELON_SLICE", 0, "MELON");
    add("PUMPKIN", 0, "PUMPKIN");
    for (String same :
        List.of("SAND", "RED_SAND", "GRAVEL", "GRANITE", "DIORITE", "ANDESITE", "TUFF", "CALCITE"))
      add(same, Set.of("SAND", "RED_SAND", "GRAVEL").contains(same) ? 0 : 1, same);
    for (String plant : List.of("SUGAR_CANE", "BAMBOO"))
      CAPABILITIES.put(plant, new Capability(Set.of(plant), 0, true));
  }

  private static void add(String resource, int tier, String... sources) {
    CAPABILITIES.put(resource, new Capability(Set.of(sources), tier, false));
    if (tier > 0) MINERALS.addAll(List.of(sources));
  }

  private HarvestCatalog() {}

  public static Capability capability(String resource) {
    return CAPABILITIES.get(resource);
  }

  public static boolean supported(String resource) {
    return resource.equals("LOG")
        || resource.endsWith("_LOG")
        || CAPABILITIES.containsKey(resource);
  }

  public static boolean matches(String resource, String block) {
    if (resource.equals("LOG")) return block.endsWith("_LOG") && !block.startsWith("STRIPPED_");
    if (resource.endsWith("_LOG")) return block.equals(resource);
    var capability = capability(resource);
    return capability != null && capability.sources().contains(block);
  }

  public static int required(String resource) {
    var capability = capability(resource);
    return capability == null ? 0 : capability.pickaxeTier();
  }

  public static Map<String, Capability> report() {
    return Map.copyOf(CAPABILITIES);
  }

  public static boolean mineral(String block) {
    return MINERALS.contains(block);
  }
}
