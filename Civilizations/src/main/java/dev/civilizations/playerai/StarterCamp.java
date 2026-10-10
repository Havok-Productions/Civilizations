package dev.civilizations.playerai;

import dev.civilizations.core.Pos;
import java.util.ArrayList;
import java.util.List;

/** First milestone: four two-block posts, a 3x3 roof and a workbench. No free items. */
public final class StarterCamp {
  private StarterCamp() {}

  public record Placement(Pos position, boolean workbench, Pos stand) {}

  public static List<Placement> blueprint(Pos origin) {
    List<Placement> result = new ArrayList<>();
    for (int y = 0; y < 2; y++)
      for (int x : new int[] {0, 2})
        for (int z : new int[] {0, 2}) result.add(placement(origin, x, y, z, false));
    // Center before edges, otherwise a finished roof edge obstructs the center's sightline.
    result.add(placement(origin, 1, 2, 1, false));
    for (int x = 0; x < 3; x++)
      for (int z = 0; z < 3; z++)
        if (x != 1 || z != 1) result.add(placement(origin, x, 2, z, false));
    result.add(placement(origin, 1, 0, 1, true));
    return List.copyOf(result);
  }

  private static Placement placement(Pos origin, int x, int y, int z, boolean workbench) {
    Pos stand =
        x == 0
            ? origin.add(-1, 0, z)
            : x == 2 ? origin.add(3, 0, z) : origin.add(1, 0, z == 2 ? 3 : -1);
    return new Placement(origin.add(x, y, z), workbench, stand);
  }

  public static int remainingPlanks(int built) {
    if (built < 0 || built > 18) throw new IllegalArgumentException("Invalid camp progress");
    return Math.max(0, 17 - built) + (built < 18 ? 4 : 0);
  }
}
