package dev.civilizations.learning;

import dev.civilizations.core.*;
import dev.coreai.*;
import java.util.*;

/** Converts immutable game observations to numeric features. Never expands the host offers. */
public final class VillagerPolicies {
  private VillagerPolicies() {}

  public static List<PolicyCase.Option> jobs(
      List<Job> jobs,
      Pos at,
      String project,
      boolean food,
      boolean danger,
      java.util.function.ToIntFunction<Job> missing) {
    List<PolicyCase.Option> options = new ArrayList<>();
    for (Job job : jobs)
      options.add(
          new PolicyCase.Option(
              job.id,
              PolicyCase.features(
                  options.size() * 100,
                  "failures",
                  Math.min(1000, Math.max(0, job.failures)),
                  "distance",
                  at.distance2(job.target),
                  "missing",
                  missing.applyAsInt(job),
                  "repair",
                  job.everBuilt && job.kind == Job.Kind.PLACE ? 1 : 0,
                  "continuing",
                  job.project.equals(project) ? 1 : 0,
                  "food",
                  food && job.kind == Job.Kind.FARM ? 1 : 0,
                  "danger",
                  danger ? 1 : 0)));
    return List.copyOf(options);
  }

  public static List<PolicyCase.Option> routes(List<Pos> positions, Pos at, Pos waypoint) {
    return positions.stream()
        .map(
            p ->
                new PolicyCase.Option(
                    p.key(),
                    PolicyCase.features(
                        Math.min(1_000_000, p.distance2(waypoint) * 10 + p.distance2(at)),
                        "distance",
                        p.distance2(at),
                        "vertical",
                        Math.abs(p.y() - at.y()),
                        "detour",
                        p.distance2(waypoint))))
        .toList();
  }

  public static <T> List<T> ordered(
      List<T> original,
      CoreAiCoordinator.Choice choice,
      java.util.function.Function<T, String> id) {
    Map<String, T> offered = new HashMap<>();
    original.forEach(value -> offered.put(id.apply(value), value));
    return choice.options().stream()
        .map(o -> offered.get(o.id()))
        .filter(Objects::nonNull)
        .toList();
  }
}
