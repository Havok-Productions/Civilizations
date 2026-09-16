package dev.civilizations.design;

import dev.civilizations.core.Pos;
import java.util.*;

/** Fill observed gaps from real support upward; every added block becomes paid work. */
final class SiteFoundations {
  static void collect(DesignSite s, Pos top, Set<Pos> clear, Set<Pos> fill) {
    Pos p = top;
    while (!s.solid(p)) {
      s.require(
          s.clear(p),
          "Foundation needs a different level or layout at "
              + p.key()
              + " (found="
              + s.type(p)
              + ", desired_level="
              + top.y()
              + ")");
      s.require(!s.occupied.test(p), "Foundation fill intersects protected space at " + p.key());
      s.require(
          s.terrain.dry(p),
          "Foundation fill surroundings block preparation at " + p.key() + surroundings(s, p));
      if (!s.type(p).equals("AIR")
          && !s.type(p).equals("CAVE_AIR")
          && !s.type(p).equals("VOID_AIR")) clear.add(p);
      fill.add(p);
      s.require(
          fill.size() + s.jobs.size() < 512,
          "Foundation work exceeds compilation capacity; retain and split into stages at "
              + p.key());
      p = p.add(0, -1, 0);
    }
    s.require(
        s.terrain.dry(p),
        "Foundation base surroundings block preparation at " + p.key() + surroundings(s, p));
  }

  private static String surroundings(DesignSite s, Pos at) {
    Map<String, List<String>> result = new TreeMap<>();
    for (int x = -1; x <= 1; x++)
      for (int y = -1; y <= 1; y++)
        for (int z = -1; z <= 1; z++) {
          Pos p = at.add(x, y, z);
          if (s.terrain.fluid(p) || s.terrain.type(p).equals("UNKNOWN"))
            result.computeIfAbsent(s.terrain.type(p), k -> new ArrayList<>()).add(p.key());
        }
    return "; nearby=" + result;
  }
}
