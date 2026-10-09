package dev.civilizations.world;

import dev.civilizations.core.*;
import java.util.*;

/** Observed surface sources, independent of the searching worker's underground elevation. */
public final class SurfaceResources {
  private SurfaceResources() {}

  public record Observation(List<Pos> sources, int observedColumns, int missingColumns) {
    public Observation {
      sources = List.copyOf(sources);
    }
  }

  public static boolean applicable(String resource) {
    return resource.equals("LOG")
        || resource.endsWith("_LOG")
        || Set.of(
                "WHEAT_SEEDS", "SUGAR_CANE", "BAMBOO", "SAND", "RED_SAND", "MELON_SLICE", "PUMPKIN")
            .contains(resource);
  }

  public static Observation observe(Terrain terrain, Pos center, int radius, String resource) {
    if (!applicable(resource))
      throw new IllegalArgumentException("Not a surface resource: " + resource);
    List<Pos> sources = new ArrayList<>();
    int observed = 0, missing = 0;
    for (int x = center.x() - radius; x <= center.x() + radius; x++) {
      for (int z = center.z() - radius; z <= center.z() + radius; z++) {
        if (!terrain.available(x, z)) {
          missing++;
          continue;
        }
        observed++;
        // The captured height is evidence; neither sea level nor the villager's Y is assumed.
        // Stop at ground, roofs, fluids or unknown terrain. Do not search through structures.
        for (int y = terrain.height(x, z); ; y--) {
          Pos p = new Pos(x, y, z);
          String block = terrain.type(p);
          if (block.equals("UNKNOWN") || terrain.fluid(p)) break;
          if (MaterialSources.matches(resource, block)) {
            boolean rooted =
                !block.endsWith("_LOG")
                    || Set.of("DIRT", "GRASS_BLOCK", "PODZOL", "ROOTED_DIRT")
                        .contains(terrain.type(p.add(0, -1, 0)));
            if (rooted) sources.add(p);
          }
          if (!(terrain.clear(p)
              || block.endsWith("_LEAVES")
              || block.endsWith("_LOG")
              || block.equals("BAMBOO")
              || block.equals("SUGAR_CANE"))) break;
        }
      }
    }
    sources.sort(Comparator.comparingLong(center::distance2));
    return new Observation(sources, observed, missing);
  }
}
