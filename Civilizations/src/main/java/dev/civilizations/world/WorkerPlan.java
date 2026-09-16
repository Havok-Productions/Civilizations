package dev.civilizations.world;

import dev.civilizations.core.*;
import java.util.*;

/** The same next prerequisite is offered to the model, requested from peers, and executed. */
public final class WorkerPlan {
  private WorkerPlan() {}

  public record Prerequisite(String output, CraftingBook.Step step) {
    public String resource() {
      return ToolActions.gatheringMaterial(output, step.item());
    }
  }

  /** Both planning and execution must resolve the tool before requesting its mined ingredient. */
  public static Prerequisite next(
      CraftingBook recipes,
      String output,
      Map<String, Integer> inv,
      boolean table,
      boolean furnace) {
    Set<String> visited = new HashSet<>();
    while (visited.add(output)) {
      CraftingBook.Step step = recipes.next(output, inv, table, furnace);
      int required = HarvestCatalog.required(step.item());
      if (!step.action().equals("gather") || required <= ToolRecipes.tier(inv))
        return new Prerequisite(output, step);
      output =
          ToolRecipes.nextTool(
              required, ToolRecipes.tier(inv), inv.getOrDefault("COBBLESTONE", 0) >= 3);
    }
    throw new IllegalStateException("Cyclic mining-tool prerequisite: " + output);
  }

  public static Map<String, Integer> needed(
      CraftingBook recipes, Job job, Map<String, Integer> inv, boolean table) {
    return needed(recipes, job, inv, table, true);
  }

  public static Map<String, Integer> needed(
      CraftingBook recipes, Job job, Map<String, Integer> inv, boolean table, boolean furnace) {
    if (job == null || job.kind == Job.Kind.CLEAR || job.kind == Job.Kind.PATH) return Map.of();
    if (job.kind == Job.Kind.FARM)
      return inv.getOrDefault("WHEAT_SEEDS", 0) > 0 ? Map.of() : Map.of("WHEAT_SEEDS", 1);
    String output = job.material;
    if (job.kind == Job.Kind.MINE) {
      int tier = Math.max(1, ToolRecipes.required(job.expected));
      if (ToolRecipes.tier(inv) >= tier) return Map.of();
      output =
          ToolRecipes.nextTool(
              tier, ToolRecipes.tier(inv), inv.getOrDefault("COBBLESTONE", 0) >= 3);
    }
    Prerequisite prerequisite = next(recipes, output, inv, table, furnace);
    CraftingBook.Step step = prerequisite.step();
    if (!step.action().equals("gather")) return Map.of();
    String resource = prerequisite.resource();
    return Map.of(resource, Math.max(1, step.amount() - inv.getOrDefault(step.item(), 0)));
  }
}
