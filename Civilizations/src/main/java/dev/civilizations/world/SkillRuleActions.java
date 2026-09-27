package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.Pos;
import dev.civilizations.learning.*;
import dev.civilizations.navigation.NavigationMap;
import dev.coreai.SkillProgram;
import dev.coreai.TerrainRuleBook;
import java.util.*;
import java.util.concurrent.*;
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
      if (plugin
              .experiments()
              .rules()
              .rule(actor.getUniqueId().toString(), facts.material(), facts.state())
          != null) continue;
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
      classify(
          trial, step, p, explanation, observed, BlockObservation.capture(location(p).getBlock()));
      return;
    }
    plugin.experiments().step(trial, receipt);
    plugin.debug(trial.village, trial.worker, "learning_rule_trial", receipt);
  }

  /** Read on the block's region; completion carries immutable facts back to the worker. */
  public CompletableFuture<TerrainRuleBook.Facts> probe(Pos p) {
    var future = new CompletableFuture<TerrainRuleBook.Facts>();
    World world = actor.getWorld();
    if (p.y() < world.getMinHeight() || p.y() >= world.getMaxHeight())
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("probe_outside_world_height"));
    Runnable acquire =
        () -> {
          if (future.isDone()) return;
          try {
            if (!world.isChunkLoaded(p.x() >> 4, p.z() >> 4))
              throw new IllegalArgumentException("probe_chunk_not_loaded");
            future.complete(BlockObservation.capture(world.getBlockAt(p.x(), p.y(), p.z())));
          } catch (RuntimeException error) {
            future.completeExceptionally(error);
          }
        };
    try {
      if (Bukkit.isOwnedByCurrentRegion(world, p.x() >> 4, p.z() >> 4)) acquire.run();
      else Bukkit.getRegionScheduler().execute(plugin, world, p.x() >> 4, p.z() >> 4, acquire);
    } catch (RuntimeException error) {
      future.completeExceptionally(error);
    }
    return future.orTimeout(4, TimeUnit.SECONDS);
  }

  /** A remote read can teach physics; it never removes a block or bypasses action reach. */
  public void classify(
      RecoveryExperiments.Trial trial,
      SkillProgram.Step step,
      Pos p,
      String explanation,
      NavigationMap observed,
      TerrainRuleBook.Facts facts) {
    var cell = observed.cell(p);
    if (!facts.material().equals(cell.material())
        || !cell.state().isEmpty() && !facts.state().equals(cell.state()))
      throw new IllegalArgumentException(
          "Classification target changed since observation: "
              + cell.material()
              + "/"
              + cell.state()
              + " -> "
              + facts.material()
              + "/"
              + facts.state());
    var proposed =
        plugin
            .experiments()
            .rules()
            .stage(trial.id, trial.worker, facts, step.material(), explanation, trial.teacher);
    plugin.experiments().remember(facts, step.material(), explanation, trial.teacher);
    var receipt =
        Map.of(
            "instruction",
            step,
            "physical_probe",
            facts,
            "position",
            p,
            "proposed_rule",
            proposed,
            "status",
            "classification_verified_by_live_facts; retained_independently_of_route_outcome");
    plugin.experiments().step(trial, receipt);
    plugin.debug(trial.village, trial.worker, "learning_rule_trial", receipt);
  }

  private Location location(Pos p) {
    return new Location(actor.getWorld(), p.x(), p.y(), p.z());
  }
}
