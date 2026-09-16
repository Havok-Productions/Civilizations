package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.Pos;
import dev.civilizations.learning.*;
import dev.coreai.SkillProgram;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Villager;

/** Owns physical classification probes and editable navigation policy actions. */
public final class SkillRuleActions {
  private final CivilizationsPlugin plugin;
  private final Villager actor;

  public SkillRuleActions(CivilizationsPlugin plugin, Villager actor) {
    this.plugin = plugin;
    this.actor = actor;
  }

  public SkillContext observe(SkillContext context) {
    List<Map<String, Object>> probes = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Pos p : context.map().investigationCandidates(context.origin())) {
      Location at = location(p);
      if (!Bukkit.isOwnedByCurrentRegion(at, 1)) continue;
      var facts = BlockObservation.capture(at.getBlock());
      if (!seen.add(facts.state())) continue;
      probes.add(
          Map.of(
              "relative",
              new Pos(
                  p.x() - context.origin().x(),
                  p.y() - context.origin().y(),
                  p.z() - context.origin().z()),
              "facts",
              facts,
              "current_category",
              context.map().cell(p).kind()));
      if (probes.size() >= WorkerTuning.value(plugin, actor, "observation.probe_limit")) break;
    }
    Map<String, Object> report = new LinkedHashMap<>(context.observation());
    report.put("physical_block_probes", probes);
    report.put("tunable_parameters", WorkerTuning.report(plugin, actor));
    report.put(
        "search_execution",
        "No proposal radius ceiling. Execution uses observed loaded terrain and reports actual"
            + " snapshot/search resource exhaustion.");
    // Bound teacher context; global rules remain available to navigation, without sending 256
    // entries.
    report.put("known_rule_count", plugin.experiments().rules().snapshot().rules().size());
    var values = new TreeMap<String, Integer>();
    dev.coreai.ParameterCatalog.SPECS
        .keySet()
        .forEach(key -> values.put(key, WorkerTuning.value(plugin, actor, key)));
    String revision =
        UUID.nameUUIDFromBytes(
                new com.google.gson.Gson()
                    .toJson(values)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
    return new SkillContext(
        context.key() + ":" + plugin.getPluginMeta().getVersion() + ":" + revision,
        context.map(),
        context.origin(),
        context.goal(),
        context.reach2(),
        context.inventory(),
        context.failure(),
        Map.copyOf(report));
  }

  public void execute(
      RecoveryExperiments.Trial trial, SkillProgram.Step step, Pos p, String explanation) {
    execute(trial, step, p, explanation, trial.context.map());
  }

  public void execute(
      RecoveryExperiments.Trial trial,
      SkillProgram.Step step,
      Pos p,
      String explanation,
      dev.civilizations.navigation.NavigationMap observed) {
    var rules = plugin.experiments().rules();
    Map<String, Object> receipt;
    if (step.op() == SkillProgram.Op.TUNE) {
      int before = WorkerTuning.value(plugin, actor, step.material());
      rules.stageParameter(trial.id, trial.worker, step.material(), step.x());
      receipt =
          Map.of(
              "instruction",
              step,
              "previous",
              before,
              "value",
              step.x(),
              "status",
              "trial_scoped_parameter_edit");
    } else if (step.op() == SkillProgram.Op.SEARCH) {

      rules.stageRadius(trial.id, trial.worker, step.x());
      receipt =
          Map.of(
              "instruction",
              step,
              "search_radius",
              step.x(),
              "node_budget",
              NavigationService.searchBudget(step.x()),
              "status",
              "trial_scoped_rule_edit");
    } else {
      var facts = BlockObservation.capture(location(p).getBlock());
      if (!facts.material().equals(observed.cell(p).material()))
        throw new IllegalArgumentException("Classification target changed since observation");
      var proposed =
          rules.stage(trial.id, trial.worker, facts, step.material(), explanation, trial.teacher);
      plugin.experiments().remember(facts, step.material(), explanation, trial.teacher);
      receipt =
          Map.of(
              "instruction",
              step,
              "physical_probe",
              facts,
              "proposed_rule",
              proposed,
              "status",
              "classification_verified_by_live_facts; retained_independently_of_route_outcome");
    }
    plugin.experiments().step(trial, receipt);
    plugin.debug(trial.village, trial.worker, "learning_rule_trial", receipt);
  }

  private Location location(Pos p) {
    return new Location(actor.getWorld(), p.x(), p.y(), p.z());
  }
}
