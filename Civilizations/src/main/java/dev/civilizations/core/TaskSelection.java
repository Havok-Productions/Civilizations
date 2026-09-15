package dev.civilizations.core;

import java.util.*;

/** Prioritizes concrete feasible jobs; the model may choose only among these validated offers. */
public final class TaskSelection {
  private TaskSelection() {}

  public static List<Job> offered(
      Settlement village, String worker, Pos at, Map<String, Integer> inventory, long now) {
    return offered(village, worker, at, inventory, now, j -> missing(j, inventory));
  }

  public static List<Job> offered(
      Settlement village,
      String worker,
      Pos at,
      Map<String, Integer> inventory,
      long now,
      java.util.function.Function<Job, Map<String, Integer>> missing) {
    String project = village.taskProject(worker);
    boolean food = inventory.getOrDefault("BREAD", 0) < 3;
    boolean danger = village.needs().danger(now);
    Set<String> remembered = new HashSet<>();
    village.checkpoints(worker).forEach(step -> remembered.add(step.job()));
    return village.jobs().stream()
        .filter(j -> village.available(j.id, worker, now))
        .filter(
            j ->
                missing.apply(j).keySet().stream()
                    .noneMatch(m -> village.knowledge().blocked("resource:" + m, now)))
        .filter(
            j ->
                !(j.kind == Job.Kind.MINE
                        || missing.apply(j).containsKey("COAL")
                        || missing.apply(j).containsKey("COBBLESTONE"))
                    || ToolRecipes.tier(inventory) > 0
                    || village.stock().entrySet().stream()
                        .anyMatch(e -> e.getKey().endsWith("_LOG") && e.getValue() > 0)
                    || !village.knowledge().blocked("resource:OAK_LOG", now)
                        && !village.knowledge().blocked("resource:LOG", now))
        .sorted(
            Comparator.<Job>comparingInt(
                    j ->
                        remembered.contains(j.id)
                            ? -1
                            : j.everBuilt && j.kind == Job.Kind.PLACE
                                ? 0
                                : j.project.equals(project)
                                    ? 1
                                    : danger
                                            && (j.project.startsWith("wall-")
                                                || j.project.startsWith("design-wall-")
                                                || j.project.startsWith("design-lights-")
                                                || j.project.equals("lights"))
                                        ? 2
                                        : food && j.kind == Job.Kind.FARM
                                            ? 3
                                            : j.kind == Job.Kind.PLACE && missing.apply(j).isEmpty()
                                                ? 4
                                                : j.kind == Job.Kind.MINE ? 6 : 5)
                .thenComparingLong(j -> j.target.distance2(at)))
        .limit(12)
        .toList();
  }

  public static Map<String, Integer> missing(Job job, Map<String, Integer> inventory) {
    if (job == null
        || job.kind == Job.Kind.CLEAR
        || job.kind == Job.Kind.MINE
        || job.kind == Job.Kind.PATH) return Map.of();
    if (job.kind == Job.Kind.FARM)
      return inventory.getOrDefault("WHEAT_SEEDS", 0) > 0 ? Map.of() : Map.of("WHEAT_SEEDS", 1);
    return RecipeCatalog.missing(job.material, inventory);
  }
}
