package dev.civilizations.design;

import dev.civilizations.core.Pos;
import java.util.*;

/** Observed, paid steps for elevated preparation; failed alternatives leave no phantom jobs. */
final class SiteAccessSteps {
  static boolean build(DesignSite site, Pos target) {
    return build(site, target, new ArrayList<>());
  }

  static boolean build(DesignSite site, Pos target, List<String> failures) {
    for (int[] direction : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
      DesignSite attempt = site.copy();
      try {
        Set<Pos> fill = new HashSet<>(), clear = new HashSet<>();
        // Stand at the lowest level within ordinary vertical reach, stepping down outward.
        int top = target.y() - 4;
        for (int distance = 1; ; distance++, top--) {
          Pos p = target.add(direction[0] * distance, top - target.y(), direction[1] * distance);
          attempt.require(
              !attempt.footprint.contains(p.x() + "," + p.z()),
              "Access steps intersect the planned structure at " + p.key());
          attempt.require(
              attempt.terrain.available(p.x(), p.z()), "Access steps need more observed terrain");
          if (attempt.solid(p) && attempt.clear(p.add(0, 1, 0)) && attempt.clear(p.add(0, 2, 0)))
            break;
          attempt.require(
              attempt.clear(p.add(0, 1, 0)) && attempt.clear(p.add(0, 2, 0)),
              "Access steps need separate clearance");
          SiteFoundations.collect(attempt, p, clear, fill);
        }
        if (fill.isEmpty()) continue;
        SitePreparation.prepare(attempt, clear, fill, false);
        List<Pos> stands = attempt.standsNear(target);
        if (DesignAccess.reachable(attempt, attempt.placed, stands, true).stream()
            .noneMatch(key -> stands.stream().anyMatch(p -> p.key().equals(key)))) continue;
        site.adopt(attempt);
        return true;
      } catch (IllegalArgumentException rejected) {
        failures.add(
            "target="
                + target.key()
                + ", side="
                + Arrays.toString(direction)
                + ": "
                + rejected.getMessage());
        // Try another observed side; no edits have escaped this compilation branch.
      }
    }
    return false;
  }
}
