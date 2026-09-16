package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import dev.civilizations.learning.*;
import dev.coreai.SkillProgram;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Villager;

/** Fresh observations and a replacement instruction stream; never changes the committed goal. */
final class RecoveryRevision {
  record Result(SkillProgram program, SkillContext context) {}

  private final CivilizationsPlugin plugin;
  private final Villager actor;
  private final RecoveryExperiments.Trial trial;
  private final CompletableFuture<NavigationService.Plan> survey;
  private final Map<String, Object> failure;
  private final boolean site;
  private final String reason;
  private SkillContext context;
  private CompletableFuture<SkillProgram> program;

  RecoveryRevision(
      CivilizationsPlugin plugin,
      Villager actor,
      Settlement village,
      RecoveryExperiments.Trial trial,
      SkillProgram previous,
      int index,
      String reason,
      boolean site) {
    this.plugin = plugin;
    this.actor = actor;
    this.trial = trial;
    this.reason = reason;
    this.site = site;
    var at = actor.getLocation();
    Pos feet = new Pos(at.getBlockX(), at.getBlockY(), at.getBlockZ());
    failure = new LinkedHashMap<>();
    failure.put("reason", reason);
    failure.put("failed_index", index);
    failure.put(
        "remaining_instructions",
        previous
            .steps()
            .subList(Math.min(index, previous.steps().size()), previous.steps().size()));
    failure.put("position", feet);
    failure.put("original_goal", trial.context.goal());
    failure.put("inventory", InventoryOps.summary(actor.getInventory()));
    if (org.bukkit.Bukkit.isOwnedByCurrentRegion(actor)) {
      var worker = plugin.worker(actor.getUniqueId().toString());
      if (worker != null) failure.put("navigation_evidence", worker.navigationEvidence());
    }
    survey =
        plugin
            .navigation()
            .request(
                actor.getWorld(),
                village,
                trial.worker,
                feet,
                trial.context.goal(),
                trial.context.reach2());
    plugin.debug(trial.village, trial.worker, "recovery_revision_started", failure);
  }

  Result tick(long now, SkillRuleActions actions) {
    if (program == null) {
      if (!survey.isDone()) return null;
      var at = actor.getLocation();
      context =
          actions.observe(
              SkillContext.create(
                  survey.join().map(),
                  new Pos(at.getBlockX(), at.getBlockY(), at.getBlockZ()),
                  trial.context.goal(),
                  trial.context.reach2(),
                  InventoryOps.summary(actor.getInventory()),
                  reason));
      var report = new LinkedHashMap<String, Object>(context.observation());
      report.put(
          "verification_goal",
          site
              ? "CLEAR_SITE: original goal block must actually become AIR"
              : "ARRIVAL: reach the original goal with normal movement");
      context =
          new SkillContext(
              context.key() + (site ? ":site" : ""),
              context.map(),
              context.origin(),
              context.goal(),
              context.reach2(),
              context.inventory(),
              context.failure(),
              Map.copyOf(report));
      program = plugin.experiments().revise(trial, context, failure, now);
    }
    return program.isDone() ? new Result(program.join(), context) : null;
  }
}
