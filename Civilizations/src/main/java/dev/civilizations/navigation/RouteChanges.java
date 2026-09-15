package dev.civilizations.navigation;

import dev.civilizations.core.Pos;
import java.util.*;
import java.util.function.Function;

/** Revalidate traversed cells, while accepting the route's own verified opening/clearance. */
public final class RouteChanges {
  private RouteChanges() {}

  public record Change(Pos position, NavigationMap.Cell snapshot, NavigationMap.Cell live) {}

  public static List<Change> inspect(
      NavigationMap map,
      List<TerrainRouteSearch.Step> steps,
      Function<Pos, NavigationMap.Cell> live) {
    Set<Pos> cleared = new HashSet<>(), opened = new HashSet<>(), positions = new LinkedHashSet<>();
    for (var step : steps) {
      cleared.addAll(step.clear());
      opened.addAll(step.open());
      for (int dy = -1; dy <= 2; dy++) positions.add(step.feet().add(0, dy, 0));
    }
    List<Change> changes = new ArrayList<>();
    for (Pos p : positions) {
      var before = map.cell(p);
      var after = live.apply(p);
      if (after == null || after.kind() == NavigationMap.Kind.UNKNOWN) continue;
      if (cleared.contains(p) && Set.of("AIR", "CAVE_AIR", "VOID_AIR").contains(after.material()))
        continue;
      if (opened.contains(p)
          && before.material().equals(after.material())
          && before
              .state()
              .replace("open=false", "open=true")
              .equals(after.state().replace("open=false", "open=true"))) continue;
      if (!before.material().equals(after.material())
          || !before.state().isEmpty() && !before.state().equals(after.state()))
        changes.add(new Change(p, before, after));
    }
    return List.copyOf(changes);
  }
}
