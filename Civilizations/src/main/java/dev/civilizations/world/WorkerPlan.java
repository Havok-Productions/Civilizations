package dev.civilizations.world;

import dev.civilizations.core.*;
import java.util.*;

/** The same next prerequisite is offered to the model, requested from peers, and executed. */
public final class WorkerPlan {
  private WorkerPlan() {}

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
      output = tier >= 2 && ToolRecipes.tier(inv) > 0 ? "STONE_PICKAXE" : "WOODEN_PICKAXE";
    }
    CraftingBook.Step step = recipes.next(output, inv, table, furnace);
    if (step.action().equals("gather")
        && Set.of("COAL", "COBBLESTONE").contains(step.item())
        && ToolRecipes.tier(inv) == 0) {
      output = "WOODEN_PICKAXE";
      step = recipes.next(output, inv, table, furnace);
    }
    if (!step.action().equals("gather")) return Map.of();
    String resource = ToolActions.gatheringMaterial(output, step.item());
    return Map.of(resource, Math.max(1, step.amount() - inv.getOrDefault(step.item(), 0)));
  }
}
