package dev.civilizations.design;

import dev.civilizations.core.*;
import java.util.*;

/** Survey bounds include construction and its access origin, without a mirrored empty area. */
public record DesignSurvey(Pos center, int radius) {
  public static DesignSurvey proposal(Blueprint b, Pos origin) {
    return proposal(b, origin, true);
  }

  public static DesignSurvey trial(Blueprint b, Pos origin) {
    return proposal(b, origin, false);
  }

  private static DesignSurvey proposal(Blueprint b, Pos origin, boolean includeAccessOrigin) {
    List<Blueprint.Point> points = new ArrayList<>();
    if (includeAccessOrigin) points.add(new Blueprint.Point(0, 0));
    if (Set.of("wall", "path", "lights").contains(b.kind())) {
      points.addAll(b.points());
      if (b.kind().equals("wall")) points.add(new Blueprint.Point(b.x(), b.z()));
    } else {
      points.add(new Blueprint.Point(b.x(), b.z()));
      if (b.kind().equals("mine")) {
        int length = Math.addExact(b.depth(), b.width());
        int dx = b.direction().equals("east") ? 1 : b.direction().equals("west") ? -1 : 0;
        int dz = b.direction().equals("south") ? 1 : b.direction().equals("north") ? -1 : 0;
        points.add(
            new Blueprint.Point(Math.subtractExact(b.x(), dx), Math.subtractExact(b.z(), dz)));
        points.add(
            new Blueprint.Point(
                Math.addExact(b.x(), Math.multiplyExact(dx, length)),
                Math.addExact(b.z(), Math.multiplyExact(dz, length))));
      } else
        points.add(
            new Blueprint.Point(Math.addExact(b.x(), b.width()), Math.addExact(b.z(), b.depth())));
    }
    return bounds(origin, points, 6);
  }

  /** Recovery must observe the actual bed/chest neighborhoods used to suggest local defenses. */
  public static DesignSurvey neighborhood(Settlement village, Pos origin) {
    List<Pos> hubs = new ArrayList<>(village.beds());
    hubs.addAll(village.chests());
    hubs.add(DesignFocus.select(village));
    hubs.add(origin);
    return bounds(
        origin,
        hubs.stream()
            .map(
                p ->
                    new Blueprint.Point(
                        Math.subtractExact(p.x(), origin.x()),
                        Math.subtractExact(p.z(), origin.z())))
            .toList(),
        26);
  }

  private static DesignSurvey bounds(Pos origin, List<Blueprint.Point> points, int margin) {
    long minX = points.stream().mapToLong(Blueprint.Point::x).min().orElse(0);
    long maxX = points.stream().mapToLong(Blueprint.Point::x).max().orElse(0);
    long minZ = points.stream().mapToLong(Blueprint.Point::z).min().orElse(0);
    long maxZ = points.stream().mapToLong(Blueprint.Point::z).max().orElse(0);
    long x = Math.floorDiv(minX + maxX, 2), z = Math.floorDiv(minZ + maxZ, 2);
    long radius = Math.max(Math.max(x - minX, maxX - x), Math.max(z - minZ, maxZ - z)) + margin;
    Pos center =
        new Pos(Math.toIntExact(origin.x() + x), origin.y(), Math.toIntExact(origin.z() + z));
    int r = Math.toIntExact(Math.max(32, radius));
    Math.subtractExact(center.x(), r);
    Math.addExact(center.x(), r);
    Math.subtractExact(center.z(), r);
    Math.addExact(center.z(), r);
    return new DesignSurvey(center, r);
  }
}
