package dev.civilizations.navigation;

import dev.civilizations.core.Pos;
import java.util.*;
import java.util.function.Function;

/** Breathable, cardinal swimming steps to a shore. Does not accept submerged shortcuts. */
public final class SurfaceEscape {
  public enum Cell {
    WATER,
    LAND,
    BLOCKED
  }

  public record Route(List<Pos> steps, int observed, String reason) {}

  private SurfaceEscape() {}

  public static Route find(Pos start, Pos goal, int radius, Function<Pos, Cell> observe) {
    Map<Pos, Cell> cells = new HashMap<>();
    Function<Pos, Cell> cell = p -> cells.computeIfAbsent(p, observe);
    if (cell.apply(start) != Cell.WATER)
      return new Route(List.of(), cells.size(), "no_breathable_surface_start");
    var queue = new ArrayDeque<Pos>();
    var prior = new HashMap<Pos, Pos>();
    queue.add(start);
    prior.put(start, start);
    while (!queue.isEmpty()) {
      if (cells.size() >= 8192)
        return new Route(List.of(), cells.size(), "surface_observation_budget_exhausted");
      Pos at = queue.removeFirst();
      List<Pos> adjacent = new ArrayList<>();
      for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
        for (int dy : new int[] {0, 1, -1}) adjacent.add(at.add(d[0], dy, d[1]));
      adjacent.sort(
          Comparator.comparingLong(p -> goal == null ? start.distance2(p) : goal.distance2(p)));
      for (Pos p : adjacent) {
        if (Math.abs(p.x() - start.x()) > radius
            || Math.abs(p.z() - start.z()) > radius
            || Math.abs(p.y() - start.y()) > 2
            || prior.containsKey(p)) continue;
        Cell kind = cell.apply(p);
        if (kind == Cell.BLOCKED) continue;
        // A rise needs space above our current head. A fall must not enter deep water.
        if (p.y() > at.y() && cell.apply(at.add(0, 1, 0)) == Cell.BLOCKED) continue;
        prior.put(p, at);
        if (kind == Cell.LAND) {
          List<Pos> route = new ArrayList<>();
          for (Pos step = p; !step.equals(start); step = prior.get(step)) route.add(step);
          Collections.reverse(route);
          return new Route(List.copyOf(route), cells.size(), "observed_surface_exit");
        }
        queue.addLast(p);
      }
    }
    return new Route(List.of(), cells.size(), "no_observed_surface_exit");
  }
}
