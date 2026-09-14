package dev.civilizations.core;

/** Bounded intermediate goals; partial paths count only when their endpoint advances the route. */
public final class RouteProgress {
  private RouteProgress() {}

  public static Pos waypoint(Pos from, Pos goal) {
    double distance = Math.sqrt(from.horizontal2(goal));
    if (distance <= 12) return goal;
    double scale = 12 / distance;
    return new Pos(
        from.x() + (int) Math.round((goal.x() - from.x()) * scale),
        from.y() + (int) Math.round(Math.clamp(goal.y() - from.y(), -2, 2)),
        from.z() + (int) Math.round((goal.z() - from.z()) * scale));
  }

  public static boolean advances(Pos from, Pos end, Pos goal) {
    return from.distance2(end) >= 4 && end.distance2(goal) + 4 < from.distance2(goal);
  }
}
