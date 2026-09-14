package dev.civilizations.design;

import dev.civilizations.core.*;
import dev.civilizations.world.*;
import java.util.*;
import java.util.function.Predicate;

/** Coarse observations for model planning. Execution uses a fresh, exact block snapshot. */
public final class TerrainMap {
  private TerrainMap() {}

  public static Map<String, Object> capture(Terrain t, Settlement v, Predicate<Pos> occupied) {
    Pos c = v.center();
    List<String> rows = new ArrayList<>();
    List<List<Integer>> heights = new ArrayList<>();
    for (int z = -24; z <= 24; z += 3) {
      StringBuilder row = new StringBuilder();
      List<Integer> hs = new ArrayList<>();
      for (int x = -24; x <= 24; x += 3) {
        int wx = c.x() + x, wz = c.z() + z;
        int y = t.groundHeight(wx, wz);
        hs.add(y - c.y());
        char symbol = '.';
        for (int dx = 0; dx < 3; dx++)
          for (int dz = 0; dz < 3; dz++) {
            int px = wx + dx, pz = wz + dz, h = t.groundHeight(px, pz);
            Pos ground = new Pos(px, h, pz);
            char cell =
                !t.available(px, pz) || t.type(ground).equals("UNKNOWN")
                    ? '?'
                    : occupied.test(ground) || occupied.test(ground.add(0, 1, 0))
                        ? '#'
                        : t.fluid(ground)
                            ? '~'
                            : t.type(ground).equals("WHEAT") || t.type(ground).equals("FARMLAND")
                                ? 'f'
                                : !t.natural(ground) && !t.type(ground).equals("DIRT_PATH")
                                        || !t.clear(ground.add(0, 1, 0))
                                    ? '#'
                                    : Math.abs(h - y) > 1 ? '^' : '.';
            if (priority(cell) > priority(symbol)) symbol = cell;
          }
        row.append(symbol);
      }
      rows.add(row.toString());
      heights.add(hs);
    }
    return Map.of(
        "origin",
        c,
        "offset_start",
        -24,
        "cell_size",
        3,
        "rows_z_then_x",
        rows,
        "height_offsets",
        heights,
        "legend",
        ". open natural ground; # structure/reserved; ~ water; f existing farm; ^ slope; ? unknown."
            + " Each character summarizes a 3x3 area. Exact validation may still reject a site.");
  }

  private static int priority(char c) {
    return switch (c) {
      case '?' -> 6;
      case '#' -> 5;
      case '~' -> 4;
      case '^' -> 3;
      case 'f' -> 2;
      default -> 1;
    };
  }
}
