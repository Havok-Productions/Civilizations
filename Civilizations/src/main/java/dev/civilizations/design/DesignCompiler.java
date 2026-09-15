package dev.civilizations.design;

import dev.civilizations.core.*;
import dev.civilizations.world.*;
import java.util.*;
import java.util.function.Predicate;

/** Converts one declarative design into a reviewed, atomic project with a material estimate. */
public final class DesignCompiler {
  public record Result(
      List<Job> jobs,
      Set<Pos> reservations,
      Map<String, Integer> materials,
      Set<Pos> construction) {}

  public Result compile(
      Blueprint b,
      Terrain terrain,
      Pos center,
      String project,
      Predicate<Pos> occupied,
      List<Pos> landmarks) {
    validatePurpose(b, center, landmarks);
    DesignSite s = new DesignSite(terrain, center, project, occupied);
    switch (b.kind()) {
      case "house" -> HousingDesign.build(s, b);
      case "wall" -> RouteDesign.wall(s, b, landmarks);
      case "path" -> RouteDesign.path(s, b);
      case "farm" -> UtilityDesign.farm(s, b);
      case "lights" -> UtilityDesign.lights(s, b);
      case "mine" -> UtilityDesign.mine(s, b);
      default -> throw new IllegalArgumentException("No construction requested");
    }
    s.require(!s.jobs.isEmpty(), "Design has no new work");
    DesignAccess.verify(s);
    // Simulate crafting across the whole project, reusing recipe leftovers between jobs.
    Map<String, Integer> supply = new TreeMap<>(), carry = new HashMap<>();
    for (Job j : s.jobs) {
      if (j.kind == Job.Kind.CLEAR || j.kind == Job.Kind.MINE || j.kind == Job.Kind.PATH) continue;
      if (j.kind == Job.Kind.FARM) {
        if (!terrain.type(j.target).equals("WHEAT")) supply.merge("WHEAT_SEEDS", 1, Integer::sum);
        continue;
      }
      Map<String, Integer> cost = RecipeCatalog.cost(j.material, carry);
      cost.forEach(
          (m, n) -> {
            int missing = Math.max(0, n - carry.getOrDefault(m, 0));
            if (missing > 0) {
              supply.merge(m, missing, Integer::sum);
              carry.merge(m, missing, Integer::sum);
            }
            carry.merge(m, -n, Integer::sum);
          });
      RecipeCatalog.leftovers(j.material, cost).forEach((m, n) -> carry.merge(m, n, Integer::sum));
    }
    return new Result(
        List.copyOf(s.jobs),
        Set.copyOf(s.reserved),
        Map.copyOf(supply),
        Set.copyOf(s.construction));
  }

  public static boolean insideWall(Blueprint wall, Pos center, Pos p) {
    return RouteDesign.inside(wall.points(), p.x() - center.x(), p.z() - center.z());
  }

  /** Existing defense requirement can be checked before spending resources on terrain capture. */
  public static void validatePurpose(Blueprint b, Pos origin, List<Pos> landmarks) {
    if (b.kind().equals("wall")
        && (landmarks.isEmpty() ? List.of(origin) : landmarks)
            .stream().noneMatch(p -> insideWall(b, origin, p)))
      throw new IllegalArgumentException(
          "Wall must protect a village bed, chest, or the work center; contour encloses none of"
              + " these. Check coordinate_space against the saved origin "
              + origin.key()
              + "; other neighborhoods may have their own defenses");
  }
}
