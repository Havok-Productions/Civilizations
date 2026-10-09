package dev.civilizations.core;

import java.util.*;
import java.util.function.Predicate;

/** Orders already-admitted steps; claims, dependency checks and execution remain with the host. */
public final class ProjectExecution {
  private ProjectExecution() {}

  public static List<Job> order(List<Job> offered, String currentProject, Predicate<Job> ready) {
    // Stable buckets retain learned/local ranking inside each priority class.
    List<Job> continuing = new ArrayList<>(),
        supplied = new ArrayList<>(),
        prerequisites = new ArrayList<>();
    for (var job : offered) {
      if (!currentProject.isEmpty() && job.project.equals(currentProject)) continuing.add(job);
      else if (ready.test(job)) supplied.add(job);
      else prerequisites.add(job);
    }
    continuing.addAll(supplied);
    continuing.addAll(prerequisites);
    return List.copyOf(continuing);
  }
}
