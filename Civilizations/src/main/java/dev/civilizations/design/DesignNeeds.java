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
    if (v.population() == 0 || v.paused() || designs.size() >= 32) return Set.of();
    if (active < activeLimit) {
      if (v.beds().size() < v.population() && !jobs.stream().anyMatch(j -> house(j) && !j.complete))
        result.add("house");
      boolean shelter =
          v.beds().size() < v.population() && !jobs.stream().anyMatch(j -> house(j) && !j.complete);
      if (jobs.stream().noneMatch(DesignNeeds::wall)
          || shelter
              && jobs.stream().noneMatch(j -> wall(j) && !j.complete)
              && designs.stream().filter(d -> d.kind().equals("wall")).count() < 3)
        result.add("wall");
      if (jobs.stream().noneMatch(j -> j.kind == Job.Kind.FARM)
          && v.stock().getOrDefault("BREAD", 0) < v.population() * 3) result.add("farm");
      if (jobs.stream().noneMatch(j -> j.material.equals("TORCH") && !j.complete)
          && designs.stream().filter(d -> d.kind().equals("lights")).count() < 3)
        result.add("lights");
      if (v.chest() != null
          && designs.stream().filter(d -> d.kind().equals("path")).count() < 4
          && jobs.stream().noneMatch(j -> j.kind == Job.Kind.PATH && !j.complete))
        result.add("path");
    }
    // Reserve one extra supply-project slot so two unfunded builds cannot prevent a needed mine.
    if (active < activeLimit + 1
        && (jobs.stream().anyMatch(j -> j.kind == Job.Kind.PLACE && !j.complete)
            || v.supplyNeeds(System.currentTimeMillis()).stream()
                .anyMatch(s -> Set.of("COAL", "COBBLESTONE").contains(s.material())))
        && jobs.stream().noneMatch(j -> j.kind == Job.Kind.MINE && !j.complete)
        && designs.stream().filter(d -> d.kind().equals("mine")).count() < 3) result.add("mine");
    return Set.copyOf(result);
  }

  private static boolean house(Job j) {
    return j.project.split("@", 2)[0].equals("house") || j.project.startsWith("design-house-");
  }

  private static boolean wall(Job j) {
    return j.project.startsWith("wall-") || j.project.startsWith("design-wall-");
  }
}
