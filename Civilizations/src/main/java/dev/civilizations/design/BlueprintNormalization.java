package dev.civilizations.design;

import java.util.*;

/** Equivalent contour notation and a usable entrance, with every adjustment recorded. */
public final class BlueprintNormalization {
  public record Result(Blueprint blueprint, List<String> adjustments) {}

  private BlueprintNormalization() {}

  public static Result prepare(Blueprint original) {
    if (!Set.of("wall", "path").contains(original.kind())) return new Result(original, List.of());
    List<Blueprint.Point> points = new ArrayList<>();
    for (var point : original.points())
      if (points.isEmpty() || !points.getLast().equals(point)) points.add(point);
    if (original.kind().equals("wall")
        && points.size() > 1
        && points.getFirst().equals(points.getLast())) points.removeLast();
    List<String> changes = new ArrayList<>();
    if (!points.equals(original.points()))
      changes.add("Removed duplicate route points without changing the contour");
    Blueprint b =
        new Blueprint(
            original.kind(),
            original.purpose(),
            original.x(),
            original.z(),
            original.width(),
            original.depth(),
            original.height(),
            original.direction(),
            List.copyOf(points));
    b.validateGeometry();
    if (!b.kind().equals("wall")) return new Result(b, List.copyOf(changes));
    List<Blueprint.Point> ring = RouteDesign.line(b, true);
    Blueprint.Point requested = new Blueprint.Point(b.x(), b.z());
    Blueprint.Point entrance = null;
    String direction = b.direction();
    long best = Long.MAX_VALUE;
    for (int i = 0; i < ring.size(); i++) {
      var prev = ring.get(Math.floorMod(i - 1, ring.size()));
      var next = ring.get((i + 1) % ring.size());
      if (prev.x() != next.x() && prev.z() != next.z()) continue;
      var point = ring.get(i);
      long dx = (long) point.x() - requested.x(), dz = (long) point.z() - requested.z();
      long distance = Math.abs(dx) + Math.abs(dz);
      if (distance >= best) continue;
      best = distance;
      entrance = point;
      boolean eastWest = prev.x() == next.x();
      direction =
          eastWest
              ? Set.of("east", "west").contains(b.direction()) ? b.direction() : "east"
              : Set.of("north", "south").contains(b.direction()) ? b.direction() : "north";
    }
    if (entrance == null)
      throw new IllegalArgumentException("Wall needs a straight segment for a usable entrance");
    if (!entrance.equals(requested) || !direction.equals(b.direction())) {
      changes.add(
          "Moved/oriented the gate on the nearest straight wall segment: "
              + entrance.x()
              + ","
              + entrance.z()
              + " facing "
              + direction);
      b =
          new Blueprint(
              b.kind(),
              b.purpose(),
              entrance.x(),
              entrance.z(),
              b.width(),
              b.depth(),
              b.height(),
              direction,
              b.points());
    }
    return new Result(b, List.copyOf(changes));
  }
}
