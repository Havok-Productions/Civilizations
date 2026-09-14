package dev.civilizations.design;

import dev.civilizations.core.*;
import java.util.*;

/** Need gates prevent repeated speculative construction while prior work is unfinished. */
public final class DesignNeeds {
  private DesignNeeds() {}

  public static Set<String> allowed(Settlement v, int activeLimit) {
    List<Job> jobs = v.jobs();
    List<DesignRecord> designs = v.designs();
    Set<String> result = new LinkedHashSet<>();
    long active = designs.stream().filter(d -> !v.allComplete(d.project())).count();
    if (v.population() == 0 || v.paused()) return Set.of();
    // Missing defenses must not wait behind a backlog of unfunded houses after village merges.
    if (DesignFocus.needsDefense(v) && jobs.stream().noneMatch(j -> wall(j) && !j.complete))
      result.add("wall");
    if (active < activeLimit) {
      if (v.beds().size() < v.population() && !jobs.stream().anyMatch(j -> house(j) && !j.complete))
        result.add("house");
      if (jobs.stream().noneMatch(j -> j.kind == Job.Kind.FARM)
          && v.stock().getOrDefault("BREAD", 0) < v.population() * 3) result.add("farm");
      if (jobs.stream().noneMatch(j -> j.material.equals("TORCH") && !j.complete))
        result.add("lights");
      if (v.chest() != null && jobs.stream().noneMatch(j -> j.kind == Job.Kind.PATH && !j.complete))
        result.add("path");
    }
    // Reserve one extra supply-project slot so two unfunded builds cannot prevent a needed mine.
    if ((jobs.stream().anyMatch(j -> j.kind == Job.Kind.PLACE && !j.complete)
            || v.supplyNeeds(System.currentTimeMillis()).stream()
                .anyMatch(s -> Set.of("COAL", "COBBLESTONE").contains(s.material())))
        && jobs.stream().noneMatch(j -> j.kind == Job.Kind.MINE && !j.complete)) result.add("mine");
    return Set.copyOf(result);
  }

  private static boolean house(Job j) {
    return j.project.split("@", 2)[0].equals("house") || j.project.startsWith("design-house-");
  }

  private static boolean wall(Job j) {
    return j.project.startsWith("wall-") || j.project.startsWith("design-wall-");
  }
}
