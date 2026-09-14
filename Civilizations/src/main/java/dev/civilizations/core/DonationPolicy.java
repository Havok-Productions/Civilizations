package dev.civilizations.core;

import java.util.*;

/** Small, useful player gifts; unfinished projects never authorize speculative stockpiling. */
public final class DonationPolicy {
  private DonationPolicy() {}

  public static int desired(String material, Map<String, Integer> inventory, boolean uncommitted) {
    if (!uncommitted) return 0;
    int cap =
        material.endsWith("_LOG")
                || material.endsWith("_PLANKS")
                || Set.of("COAL", "COBBLESTONE", "STICK", "TORCH").contains(material)
            ? 16
            : material.equals("WHEAT")
                ? 9
                : material.equals("WHEAT_SEEDS")
                    ? 8
                    : Set.of("BREAD", "WHITE_WOOL").contains(material)
                        ? 3
                        : material.endsWith("_PICKAXE")
                                || Set.of("CRAFTING_TABLE", "CHEST", "WHITE_BED").contains(material)
                            ? 1
                            : 0;
    return Math.max(0, cap - inventory.getOrDefault(material, 0));
  }
}
