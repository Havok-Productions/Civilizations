package dev.civilizations.learning;

import dev.civilizations.core.Pos;
import dev.civilizations.navigation.NavigationMap;
import java.util.*;

/** Compact measured geometry; sampled columns are explicit, never an inferred clear route. */
public final class RecoveryTerrainView {
  private RecoveryTerrainView() {}

  public static char symbol(NavigationMap.Cell cell) {
    // Protection restricts mutations, not movement through empty reserved space or an open gate.
    if (cell.kind() != NavigationMap.Kind.AIR
        && cell.kind() != NavigationMap.Kind.OPENABLE
        && cell.kind() != NavigationMap.Kind.HAZARD
        && cell.kind() != NavigationMap.Kind.FLUID) {
      if (cell.removal().equals("protected_or_reserved")) return 'P';
      if (cell.removal().equals("structure_or_support")) return 'B';
    }
    return switch (cell.kind()) {
      case AIR -> '.';
      case SOLID -> '#';
      case SOFT -> 's';
      case OPENABLE -> 'D';
      case HAZARD -> '!';
      case FLUID -> '~';
      case OBSTACLE -> 'X';
      case CLEARABLE -> 'c';
      case UNCLASSIFIED -> 'u';
      case UNKNOWN -> '?';
    };
  }

  public static Map<String, Object> describe(NavigationMap map, Pos origin, Pos focus) {
    Pos relative = relative(focus, origin);
    List<List<Integer>> columns = new ArrayList<>();
    // Explicit samples keep a distant goal from expanding the prompt with every intervening voxel.
    for (int i = 0; i <= 8; i++) {
      var column =
          List.of(
              (int) Math.round(relative.x() * (i / 8.0)),
              (int) Math.round(relative.z() * (i / 8.0)));
      if (!columns.contains(column)) columns.add(column);
    }
    int lower = Math.max(map.center.y() - map.vertical, Math.min(origin.y(), focus.y()) - 2);
    int upper = Math.min(map.center.y() + map.vertical, Math.max(origin.y(), focus.y()) + 2);
    List<String> bands = new ArrayList<>();
    String last = null;
    int start = lower;
    for (int y = lower; y <= upper + 1; y++) {
      String row = null;
      if (y <= upper) {
        var text = new StringBuilder();
        for (var column : columns)
          text.append(
              symbol(map.cell(new Pos(origin.x() + column.get(0), y, origin.z() + column.get(1)))));
        row = text.toString();
      }
      if (!Objects.equals(last, row)) {
        if (last != null)
          bands.add((start - origin.y()) + ".." + (y - 1 - origin.y()) + ":" + last);
        start = y;
        last = row;
      }
    }
    List<Map<String, Object>> obstacles = new ArrayList<>();
    for (int dy = 0; dy <= 2; dy++)
      for (int dx = -2; dx <= 2; dx++)
        for (int dz = -2; dz <= 2; dz++) {
          Pos p = focus.add(dx, dy, dz);
          var cell = map.cell(p);
          if (cell.kind() != NavigationMap.Kind.AIR && cell.kind() != NavigationMap.Kind.UNKNOWN)
            obstacles.add(cell(map, origin, p));
        }
    obstacles.sort(
        Comparator.comparingLong(value -> ((Pos) value.get("relative")).distance2(relative)));
    return Map.of(
        "focus", cell(map, origin, focus),
        "support_below_focus", cell(map, origin, focus.add(0, -1, 0)),
        "bounds_relative",
            List.of(
                relative(map.center.add(-map.radius, -map.vertical, -map.radius), origin),
                relative(map.center.add(map.radius, map.vertical, map.radius), origin)),
        "slice_columns_xz", columns,
        "slice_y_bands", bands,
        "slice_note",
            "y_min..y_max:symbols at listed relative x,z columns; gaps between sampled columns are"
                + " not surveyed by this slice",
        "near_focus_obstacles", obstacles.stream().limit(10).toList(),
        "obstacle_count", obstacles.size());
  }

  public static List<Map<String, Object>> nearbyObstacles(NavigationMap map, Pos origin) {
    List<Pos> positions = new ArrayList<>();
    for (int x = -4; x <= 4; x++)
      for (int z = -4; z <= 4; z++)
        for (int y = 0; y <= 2; y++) {
          Pos p = origin.add(x, y, z);
          var kind = map.cell(p).kind();
          if (kind != NavigationMap.Kind.AIR && kind != NavigationMap.Kind.UNKNOWN)
            positions.add(p);
        }
    positions.sort(Comparator.comparingLong(origin::distance2));
    return positions.stream().limit(8).map(p -> cell(map, origin, p)).toList();
  }

  private static Map<String, Object> cell(NavigationMap map, Pos origin, Pos p) {
    var cell = map.cell(p);
    return Map.of(
        "relative",
        relative(p, origin),
        "material",
        cell.material(),
        "kind",
        cell.kind(),
        "removal",
        cell.removal());
  }

  private static Pos relative(Pos p, Pos origin) {
    return new Pos(p.x() - origin.x(), p.y() - origin.y(), p.z() - origin.z());
  }
}
