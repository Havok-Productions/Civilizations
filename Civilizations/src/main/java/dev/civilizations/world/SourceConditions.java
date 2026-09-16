package dev.civilizations.world;

import java.util.*;
import org.bukkit.block.Block;

/** Captures exactly the neighborhood used by fluid and falling-block harvest checks. */
final class SourceConditions {
  private SourceConditions() {}

  static String signature(Block block) {
    var state = new StringBuilder();
    for (int x = -1; x <= 1; x++)
      for (int y = -1; y <= 1; y++)
        for (int z = -1; z <= 1; z++)
          state.append(block.getRelative(x, y, z).getBlockData().getAsString()).append(';');
    return state.toString();
  }

  static List<Map<String, Object>> fluids(Block block) {
    var fluids = new ArrayList<Map<String, Object>>();
    for (int x = -1; x <= 1; x++)
      for (int y = -1; y <= 1; y++)
        for (int z = -1; z <= 1; z++) {
          Block at = block.getRelative(x, y, z);
          if (at.isLiquid()
              || at.getBlockData() instanceof org.bukkit.block.data.Waterlogged w
                  && w.isWaterlogged())
            fluids.add(
                Map.of("offset", List.of(x, y, z), "state", at.getBlockData().getAsString()));
        }
    return List.copyOf(fluids);
  }
}
