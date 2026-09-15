package dev.civilizations.design;

import dev.civilizations.core.*;
import dev.civilizations.world.*;
import java.util.*;

/** Model-positioned food, lighting, and mining projects with bounded physical operations. */
final class UtilityDesign {
  static void lights(DesignSite s, Blueprint b) {
    s.require(!b.points().isEmpty(), "Lighting needs a site");
    for (Blueprint.Point point : b.points()) {
      Pos ground = s.ground(point.x(), point.z()), p = ground.add(0, 1, 0);
      s.require(s.terrain.natural(ground), "Torch needs natural solid support");
      s.reserve(ground);
      s.add(Job.Kind.PLACE, p, s.standNear(p), "TORCH", null, 0);
    }
  }

  static void farm(DesignSite s, Blueprint b) {
    s.require(b.width() > 0 && b.depth() > 0, "Farm footprint must have area");
    for (int x = 0; x < b.width(); x++)
      for (int z = 0; z < b.depth(); z++) {
        Pos ground = s.ground(b.x() + x, b.z() + z);
        if (s.terrain.type(ground).equals("WHEAT")) ground = ground.add(0, -1, 0);
        Pos crop = ground.add(0, 1, 0);
        s.require(
            Set.of("DIRT", "GRASS_BLOCK", "FARMLAND").contains(s.terrain.type(ground))
                && FarmPlanner.hydrated(s.terrain, ground),
            "Farm needs dirt/farmland within four blocks of existing water");
        s.reserve(ground);
        s.reserve(crop);
        s.open(crop.add(0, 1, 0));
        s.require(s.clear(crop) || s.terrain.type(crop).equals("WHEAT"), "Crop plot obstructed");
        Pos stand = null;
        for (int[] d : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
          Pos p = crop.add(d[0], 0, d[1]);
          if (s.clear(p) && s.clear(p.add(0, 1, 0)) && s.solid(p.add(0, -1, 0))) {
            stand = p;
            break;
          }
        }
        s.require(
            stand != null, "Farm needs a clear adjacent work path; use a narrower bed of crops");
        s.add(Job.Kind.FARM, crop, stand, "WHEAT", null, s.jobs.size());
      }
  }

  static void mine(DesignSite s, Blueprint b) {
    s.require(
        b.depth() > 0 && b.width() >= 0,
        "Mine needs positive depth and a nonnegative gallery length");
    Pos ground = s.ground(b.x(), b.z());
    s.require(s.terrain.natural(ground), "Mine entrance cannot be in a building or water");
    int dx = b.direction().equals("east") ? 1 : b.direction().equals("west") ? -1 : 0,
        dz = b.direction().equals("south") ? 1 : b.direction().equals("north") ? -1 : 0;
    Pos previous = s.ground(b.x() - dx, b.z() - dz).add(0, 1, 0);
    s.require(
        s.clear(previous) && s.clear(previous.add(0, 1, 0)), "Mine entrance approach is blocked");
    for (int i = 0; i < b.depth() + b.width(); i++) {
      Pos surface = s.ground(b.x() + dx * i, b.z() + dz * i);
      s.require(s.terrain.natural(surface), "Mine cannot pass below a surface building or water");
      Pos foot = ground.add(dx * i, -Math.min(i + 1, b.depth()) + 1, dz * i);
      s.require(
          s.terrain.natural(foot.add(0, -1, 0)) && Math.abs(previous.y() - foot.y()) <= 1,
          "Mine needs a continuous walkable staircase floor");
      s.reserve(foot.add(0, -1, 0));
      for (int y = 2; y >= 0; y--) {
        Pos p = foot.add(0, y, 0);
        s.reserve(p);
        s.require(s.terrain.dry(p), "Mine would open into water or unknown terrain");
        s.require(s.clear(p) || s.terrain.natural(p), "Mine intersects a non-natural block");
        if (!s.clear(p)) s.add(Job.Kind.MINE, p, previous, "", null, s.jobs.size());
      }
      previous = foot;
    }
  }
}
