package dev.civilizations.core;

import java.util.*;

/** Concrete goal steps supplied to the model and recomputed against actual inventory. */
public final class TaskPlan {
  private TaskPlan() {}

  public static Map<String, Object> describe(Job job, Map<String, Integer> inventory) {
    List<String> steps = new ArrayList<>();
    Map<String, Integer> missing = TaskSelection.missing(job, inventory);
    boolean mining =
        job.kind == Job.Kind.MINE
            || missing.containsKey("COAL")
            || missing.containsKey("COBBLESTONE");
    if (mining && ToolRecipes.tier(inventory) == 0) {
      steps.add("Gather reachable wood; craft planks, sticks and a crafting table");
      steps.add("Place/adopt a reachable crafting table; craft a wooden pickaxe");
    }
    if (missing.containsKey("COAL")) {
      steps.add(
          "Use reachable coal ore, or request a validated mine entrance and excavate ordered steps"
              + " to search for coal");
      steps.add("Verify coal was actually collected; a finished mineshaft does not guarantee coal");
    }
    if (job.kind == Job.Kind.MINE && ToolRecipes.required(job.expected) >= 2)
      steps.add(
          "Use a wooden pickaxe to collect three cobblestone; craft a stone pickaxe before"
              + " iron/copper ore");
    if (!missing.isEmpty()) steps.add("Obtain remaining ingredients: " + missing);
    if (job.material.equals("GLASS")) {
      steps.add("Observe sand on dry riverbanks, beaches or deserts; gather a safe top layer");
      steps.add(
          "Use or craft a furnace from cobblestone; load sand and real fuel; collect only actual"
              + " smelted glass");
    }
    steps.add("Execute " + job.kind + " at " + job.target.key() + " and verify the world result");
    steps.add("Finish the committed project before depositing surplus; retain tools and food");
    return Map.of("goal", job.project, "job", job.id, "steps", steps, "missing", missing);
  }
}
