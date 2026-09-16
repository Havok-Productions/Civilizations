package dev.civilizations.design;

import dev.civilizations.core.Pos;
import java.util.*;

/** Local search examples, including off-center rectangles around a real inhabited landmark. */
final class WallAlternatives {
  private WallAlternatives() {}

  static List<Blueprint> around(Pos hub, Pos origin) {
    int x = hub.x() - origin.x(), z = hub.z() - origin.z();
    List<Blueprint> result = new ArrayList<>();
    for (int radius : new int[] {3, 4, 6, 8, 12, 16, 20})
      for (int[] shape : new int[][] {{radius, radius}, {radius, 3}, {3, radius}})
        for (int dx : new int[] {0, -shape[0] + 1, shape[0] - 1})
          for (int dz : new int[] {0, -shape[1] + 1, shape[1] - 1}) {
            int left = x + dx - shape[0], right = x + dx + shape[0];
            int north = z + dz - shape[1], south = z + dz + shape[1];
            result.add(
                new Blueprint(
                    "wall",
                    "Protect the inhabited landmark at relative " + x + "," + z,
                    x + dx,
                    north,
                    0,
                    0,
                    3,
                    "north",
                    List.of(
                        new Blueprint.Point(left, north),
                        new Blueprint.Point(right, north),
                        new Blueprint.Point(right, south),
                        new Blueprint.Point(left, south))));
          }
    return result.stream().distinct().toList();
  }
}
