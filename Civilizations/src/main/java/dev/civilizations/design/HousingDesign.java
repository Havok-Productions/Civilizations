package dev.civilizations.design;

import dev.civilizations.core.*;
import java.util.*;

/** Model-chosen size, site, wall height, entrance orientation and interior bed positions. */
final class HousingDesign {
  static void build(DesignSite s, Blueprint b) {
    int w = b.width(), d = b.depth(), h = b.height();
    s.require(
        w >= 3 && d >= 4 && h >= 3,
        "House needs room for walls, a two-block bed, and standing headroom");
    SitePreparation.house(s, b);
    int floor = s.ground(b.x(), b.z()).y();
    for (int x = -1; x <= w; x++)
      for (int z = -1; z <= d; z++) {
        Pos g = s.ground(b.x() + x, b.z() + z);
        s.require(
            g.y() == floor && s.solid(g) && s.terrain.dry(g.add(0, 1, 0)),
            "House requires supported dry ground at "
                + g.key()
                + "; expected level "
                + floor
                + ", observed "
                + g.y()
                + " / "
                + s.type(g));
        boolean outside = x < 0 || z < 0 || x >= w || z >= d;
        if (outside) s.reserveAccess(g);
        else s.reserve(g);
        for (int y = 1; y <= h + 1; y++) {
          if (outside) s.openAccess(g.add(0, y, 0));
          else s.open(g.add(0, y, 0));
        }
      }
    int dx = b.direction().equals("east") ? w - 1 : b.direction().equals("west") ? 0 : w / 2;
    int dz = b.direction().equals("south") ? d - 1 : b.direction().equals("north") ? 0 : d / 2;
    Pos origin = s.ground(b.x(), b.z());
    // Lay each floor block from an actual supported adjacent location.
    for (int x = 0; x < w; x++)
      for (int z = 0; z < d; z++) {
        Pos p = origin.add(x, 1, z);
        s.add(Job.Kind.PLACE, p, s.standNear(p), "OAK_PLANKS", null, s.jobs.size());
      }
    for (int y = 2; y <= h; y++)
      for (int x = 0; x < w; x++)
        for (int z = 0; z < d; z++) {
          if (x != 0 && z != 0 && x != w - 1 && z != d - 1) continue;
          if (x == dx && z == dz && y < 4) continue;
          Pos p = origin.add(x, y, z),
              stand = origin.add(Math.clamp(x, 1, w - 2), 2, Math.clamp(z, 1, d - 2));
          s.add(Job.Kind.PLACE, p, stand, "OAK_PLANKS", null, s.jobs.size());
        }
    for (int x = 0; x < w; x++)
      for (int z = 0; z < d; z++) {
        Pos p = origin.add(x, h + 1, z),
            stand = origin.add(Math.clamp(x, 1, w - 2), 2, Math.clamp(z, 1, d - 2));
        s.add(Job.Kind.PLACE, p, stand, "OAK_PLANKS", null, s.jobs.size());
      }
    List<Blueprint.Point> beds =
        b.points().isEmpty() ? List.of(new Blueprint.Point(b.x() + 1, b.z() + 1)) : b.points();

    for (Blueprint.Point bed : beds) {
      int x = bed.x() - b.x(), z = bed.z() - b.z();
      s.require(
          x >= 1 && x <= w - 2 && z >= 1 && z <= d - 3,
          "Beds need two interior spaces facing south");
      Pos foot = origin.add(x, 2, z), head = foot.add(0, 0, 1);
      s.open(head);
      s.add(
          Job.Kind.PLACE,
          foot,
          s.standNear(foot),
          "WHITE_BED",
          "minecraft:white_bed[facing=south,part=foot]",
          s.jobs.size());
      s.placed.put(head, "WHITE_BED");
    }
    // Keep the two-block doorway open while furnishing, then fit a normal fence gate.
    Pos doorway = origin.add(dx, 2, dz),
        inside = origin.add(Math.clamp(dx, 1, w - 2), 2, Math.clamp(dz, 1, d - 2));
    s.require(s.clear(inside) && s.clear(inside.add(0, 1, 0)), "A bed obstructs the entrance");
    Pos light = null;
    for (int x = 1; x < w - 1 && light == null; x++)
      for (int z = d - 2; z >= 1; z--) {
        Pos p = origin.add(x, 2, z);
        if (!p.equals(inside) && s.clear(p)) {
          light = p;
          break;
        }
      }
    s.require(light != null, "House needs an interior lighting position");
    s.add(Job.Kind.PLACE, light, s.standNear(light), "TORCH", null, s.jobs.size());
    s.add(
        Job.Kind.PLACE,
        doorway,
        inside,
        "OAK_FENCE_GATE",
        "minecraft:oak_fence_gate[facing=" + b.direction() + ",open=false]",
        s.jobs.size());
    // Verify every bed has a connected two-block-high walking route from the entrance.
    Set<Pos> reached = new HashSet<>();
    ArrayDeque<Pos> queue = new ArrayDeque<>();
    queue.add(inside);
    reached.add(inside);
    while (!queue.isEmpty()) {
      Pos p = queue.remove();
      for (int[] step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
        Pos q = p.add(step[0], 0, step[1]);
        if (q.x() <= origin.x()
            || q.x() >= origin.x() + w - 1
            || q.z() <= origin.z()
            || q.z() >= origin.z() + d - 1) continue;
        if (s.clear(q) && s.clear(q.add(0, 1, 0)) && reached.add(q)) queue.add(q);
      }
    }
    for (Job j : s.jobs)
      if (j.material.equals("WHITE_BED"))
        s.require(
            reached.stream().anyMatch(p -> p.distance2(j.target) <= 2),
            "A bed has no walking access");
  }
}
