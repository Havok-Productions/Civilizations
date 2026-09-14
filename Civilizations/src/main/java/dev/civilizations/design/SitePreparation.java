package dev.civilizations.design;

import dev.civilizations.core.*;
import java.util.*;

/** A one-layer cut is a real ordered job, never an imaginary flat terrain observation. */
final class SitePreparation {
  static void house(DesignSite s, Blueprint b) {
    List<Pos> surface = new ArrayList<>();
    for (int x = -1; x <= b.width(); x++)
      for (int z = -1; z <= b.depth(); z++) surface.add(s.ground(b.x() + x, b.z() + z));
    int floor = surface.stream().mapToInt(Pos::y).min().orElseThrow();
    for (Pos p : surface) {
      s.require(p.y() - floor <= 1, "House grading exceeds one soil layer at " + p.key());
      if (p.y() > floor) {
        s.require(
            Set.of("DIRT", "GRASS_BLOCK").contains(s.type(p)),
            "Grading would remove a structure or non-soil block at "
                + p.key()
                + " ("
                + s.type(p)
                + ")");
        s.require(
            s.terrain.natural(p.add(0, -1, 0)) && s.terrain.dry(p),
            "Grading lacks dry solid foundation at " + p.key());
      }
    }
    for (Pos p : surface)
      if (p.y() > floor) {
        s.add(Job.Kind.CLEAR, p, s.standNear(p), "", null, s.jobs.size());
      }
  }
}
