package dev.civilizations.design;

import dev.civilizations.core.*;
import java.util.*;

/** Rectilinear model-drawn wall contours and walking paths, validated before expansion. */
final class RouteDesign {
  static List<Blueprint.Point> line(Blueprint b, boolean closed) {
    List<Blueprint.Point> points = b.points();
    if (points.size() < (closed ? 4 : 2))
      throw new IllegalArgumentException(
          "A closed wall needs at least four vertices; a path needs two");
    List<Blueprint.Point> result = new ArrayList<>();
    result.add(points.getFirst());
    for (int i = 0; i < (closed ? points.size() : points.size() - 1); i++) {
      Blueprint.Point a = points.get(i), z = points.get((i + 1) % points.size());
      if ((a.x() == z.x()) == (a.z() == z.z()))
        throw new IllegalArgumentException("Route segments must be nonzero and axis aligned");
      int dx = Integer.compare(z.x(), a.x()), dz = Integer.compare(z.z(), a.z());
      long distance = Math.abs((long) z.x() - a.x()) + Math.abs((long) z.z() - a.z());
      if (distance + result.size() > 8192)
        throw new IllegalArgumentException(
            "Route compilation capacity exceeded; split the route into buildable stages");
      int length = (int) distance;
      for (int n = 1; n <= length; n++)
        result.add(new Blueprint.Point(a.x() + dx * n, a.z() + dz * n));
      if (result.size() > 8192)
        throw new IllegalArgumentException(
            "Route compilation work budget exhausted; retain the proposal and divide construction"
                + " into stages");
    }
    if (closed) result.removeLast();
    if (new HashSet<>(result).size() != result.size())
      throw new IllegalArgumentException("Route crosses or touches itself");
    return result;
  }

  static boolean inside(List<Blueprint.Point> polygon, int x, int z) {
    boolean in = false;
    for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
      Blueprint.Point a = polygon.get(i), b = polygon.get(j);
      if ((a.z() > z) != (b.z() > z)
          && x < (double) (b.x() - a.x()) * (z - a.z()) / (b.z() - a.z()) + a.x()) in = !in;
    }
    return in;
  }

  static void wall(DesignSite s, Blueprint b, List<Pos> landmarks) {
    s.require(b.height() > 0, "A wall needs positive height");
    List<Blueprint.Point> ring = line(b, true);
    s.require(
        landmarks.isEmpty()
            ? inside(b.points(), 0, 0)
            : landmarks.stream()
                .anyMatch(p -> inside(b.points(), p.x() - s.center.x(), p.z() - s.center.z())),
        "Wall must protect a village bed, chest, or the work center; other neighborhoods may have"
            + " their own defenses");
    Blueprint.Point gate = new Blueprint.Point(b.x(), b.z());
    s.require(ring.contains(gate), "Gate must be on the wall contour");
    List<Pos> columns = new ArrayList<>(), stands = new ArrayList<>();
    for (int i = 0; i < ring.size(); i++) {
      Blueprint.Point p = ring.get(i);
      Pos ground = s.ground(p.x(), p.z());
      s.require(
          s.terrain.natural(ground) && s.terrain.dry(ground.add(0, 1, 0)),
          "Wall crosses water or a structure");
      Blueprint.Point next = ring.get((i + 1) % ring.size());
      s.require(
          Math.abs(ground.y() - s.ground(next.x(), next.z()).y()) <= 1,
          "Wall terrain changes too sharply");
      s.reserve(ground);
      for (int y = 1; y <= b.height(); y++) s.open(ground.add(0, y, 0));
      Pos stand = null;
      for (int[] d :
          new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
        if (ring.contains(new Blueprint.Point(p.x() + d[0], p.z() + d[1]))) continue;
        Pos g = s.ground(p.x() + d[0], p.z() + d[1]);
        if (Math.abs(g.y() - ground.y()) <= 1
            && ground.add(0, b.height(), 0).distance2(g.add(0, 1, 0)) <= 21
            && Math.abs(ground.y() + b.height() - g.y() - 1) <= 3
            && !s.occupied.test(g)
            && !s.occupied.test(g.add(0, 1, 0))
            && !s.occupied.test(g.add(0, 2, 0))
            && s.solid(g)
            && s.clear(g.add(0, 1, 0))
            && s.clear(g.add(0, 2, 0))) {
          stand = g.add(0, 1, 0);
          break;
        }
      }
      s.require(
          stand != null, "Wall needs a supported working position within reach on either side");
      if (p.equals(gate)) {
        Blueprint.Point prev = ring.get((i + ring.size() - 1) % ring.size());
        s.require(prev.x() == next.x() || prev.z() == next.z(), "Gate cannot occupy a corner");
        int nx = prev.x() == next.x() ? 1 : 0, nz = nx == 1 ? 0 : 1;
        s.require(
            nx == 1
                ? Set.of("east", "west").contains(b.direction())
                : Set.of("north", "south").contains(b.direction()),
            "Gate orientation must match the wall section");
        for (int sign : new int[] {-1, 1}) {
          Pos g = s.ground(p.x() + nx * sign, p.z() + nz * sign);
          s.require(
              Math.abs(g.y() - ground.y()) <= 1 && s.solid(g),
              "Gate needs accessible ground on both sides");
          s.open(g.add(0, 1, 0));
          s.open(g.add(0, 2, 0));
          s.reserve(g);
        }
      }
      columns.add(ground);
      stands.add(stand);
    }
    for (int y = 1; y <= b.height(); y++)
      for (int i = 0; i < ring.size(); i++) {
        boolean isGate = ring.get(i).equals(gate);
        if (isGate && y > 1) continue;
        s.add(
            Job.Kind.PLACE,
            columns.get(i).add(0, y, 0),
            stands.get(i),
            isGate ? "OAK_FENCE_GATE" : "COBBLESTONE",
            isGate ? "minecraft:oak_fence_gate[facing=" + b.direction() + ",open=false]" : null,
            y - 1);
      }
  }

  static void path(DesignSite s, Blueprint b) {
    s.require(b.width() == 1, "Paths currently use one-block-wide routes");
    Pos prior = null;
    for (Blueprint.Point point : line(b, false)) {
      Pos g = s.ground(point.x(), point.z());
      if (prior != null) s.require(Math.abs(g.y() - prior.y()) <= 1, "Path is too steep");
      prior = g;
      s.require(
          Set.of("DIRT", "GRASS_BLOCK", "DIRT_PATH").contains(s.terrain.type(g)),
          "Paths require dirt/grass, without excavation");
      s.open(g.add(0, 1, 0));
      s.open(g.add(0, 2, 0));
      s.reserve(g);
      s.require(s.terrain.dry(g.add(0, 1, 0)), "Path crosses water");
      if (!s.terrain.type(g).equals("DIRT_PATH"))
        s.add(Job.Kind.PATH, g, g.add(0, 1, 0), "DIRT_PATH", null, 0);
    }
  }
}
