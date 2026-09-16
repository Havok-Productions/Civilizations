package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import dev.civilizations.learning.*;
import dev.civilizations.navigation.*;
import dev.coreai.SkillProgram;
import java.util.*;
import java.util.function.BiConsumer;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;

/** Executes a model's finite skill on the owning entity region. Models never attest success. */
public final class WorkerSkillTrial {
  private final CivilizationsPlugin plugin;
  private final Villager actor;
  private final Settlement village;
  private final RouteClearance clearance;
  private final SkillRuleActions ruleActions;
  private final WorkPose workPose;
  private final BiConsumer<Boolean, String> completed;
  private RecoveryExperiments.Trial trial;
  private SkillProgram program;
  private SkillContext executionContext;
  private RecoveryRevision revision;
  private int instructionsCompleted, revisions;
  private final WorkerNavigation instructionNavigation;
  private NavigationMap instructionMap;
  private java.util.concurrent.CompletableFuture<NavigationService.Plan> observation;
  private long nextObservation;
  private Pos lastProgress;
  private int index, placed, cleared;
  private long deadline, stepStarted, nextAction;
  private long startedAt, executionAt;
  private Location previousPosition;
  private double travelled;
  private boolean observing, siteGoal;

  public WorkerSkillTrial(
      CivilizationsPlugin plugin,
      Villager actor,
      Settlement village,
      BiConsumer<Boolean, String> completed) {
    this.plugin = plugin;
    this.actor = actor;
    this.village = village;
    this.completed = completed;
    clearance = new RouteClearance(plugin, actor, village);
    instructionNavigation =
        new WorkerNavigation(
            plugin,
            actor,
            village,
            (now, reason) -> {
              if (trial != null) revise("instruction_route: " + reason);
            },
            false);
    ruleActions = new SkillRuleActions(plugin, actor);
    workPose = new WorkPose(plugin, actor);
  }

  public void failedVerification(String reason) {
    if (trial != null) {
      if (reason.startsWith("damage_event:")) finish(false, reason);
      else revise(reason);
    }
  }

  public String status() {
    return trial == null
        ? "idle"
        : revision != null
            ? "observing and revising failed recovery instruction"
            : program == null
                ? "waiting for recovery model"
                : observing
                    ? "verifying original goal"
                    : "executing " + program.steps().get(index).op();
  }

  public boolean active() {
    return trial != null;
  }

  public boolean start(NavigationMap map, Pos goal, int reach2, String failure, long now) {
    return start(map, goal, reach2, failure, now, false);
  }

  public boolean startSite(NavigationMap map, Pos goal, String failure, long now) {
    return start(map, goal, 21, failure, now, true);
  }

  private boolean start(
      NavigationMap map, Pos goal, int reach2, String failure, long now, boolean site) {
    long dayTime = actor.getWorld().getTime();
    if (active() || plugin.experiments() == null || dayTime >= 12500 && dayTime < 23500)
      return false;
    var context =
        SkillContext.create(
            map, here(), goal, reach2, InventoryOps.summary(actor.getInventory()), failure);
    context = ruleActions.observe(context);
    if (site) {
      var report = new LinkedHashMap<String, Object>(context.observation());
      report.put(
          "verification_goal",
          "CLEAR_SITE: original goal block must become AIR; arrival alone is not success. CLEAR it,"
              + " then VERIFY; no need to move if already in reach.");
      context =
          new SkillContext(
              context.key() + ":site",
              context.map(),
              context.origin(),
              context.goal(),
              context.reach2(),
              context.inventory(),
              context.failure(),
              Map.copyOf(report));
    }
    trial =
        plugin.experiments().request(village.id(), actor.getUniqueId().toString(), context, now);
    if (trial == null) return false;
    instructionMap = map;
    executionContext = context;
    revision = null;
    revisions = instructionsCompleted = 0;
    observation = null;
    nextObservation = 0;
    lastProgress = here();
    previousPosition = actor.getLocation().clone();
    travelled = 0;
    startedAt = now;
    executionAt = 0;
    siteGoal = site;
    deadline = now + 185_000;
    workPose.reset();
    index = 0;
    placed = 0;
    cleared = 0;
    observing = false;
    program = null;
    stepStarted = 0;
    nextAction = 0;
    actor.getPathfinder().stopPathfinding();
    plugin.debug(
        village.id(),
        actor.getUniqueId().toString(),
        "experiment_started",
        Map.of("trial", trial.id, "failure", failure, "goal", goal));
    return true;
  }

  public void cancel(String reason) {
    if (trial != null) {
      plugin.experiments().cancel(trial, reason);
      trial = null;
      program = null;
      revision = null;
      instructionNavigation.stop();
      observation = null;
      actor.getPathfinder().stopPathfinding();
    }
  }

  public void observe(long now) {
    if (trial == null) return;
    Location actual = actor.getLocation();
    if (previousPosition != null && actual.getWorld().equals(previousPosition.getWorld()))
      travelled += actual.distance(previousPosition);
    previousPosition = actual.clone();
    if (now > deadline) {
      finish(false, program == null ? "proposal_timeout" : "live_trial_deadline");
      return;
    }
    Pos at = here();
    // Long, productive travel must not time out merely because the final goal is distant.
    if (at.distance2(lastProgress) >= 4) {
      lastProgress = at;
      deadline = now + 90_000;
      stepStarted = now;
    }
    if (siteGoal) {
      if (observing
          && Bukkit.isOwnedByCurrentRegion(location(trial.context.goal()), 1)
          && location(trial.context.goal()).getBlock().getType().isAir())
        finish(true, "verified_original_site_cleared");
      return;
    }
    if (observing
        && at.distance2(trial.context.goal()) <= trial.context.reach2()
        && Math.abs(at.y() - trial.context.goal().y()) <= 3
        && !at.equals(trial.context.origin())
        && !actor.getLocation().getBlock().isLiquid())
      finish(true, "verified_arrival_at_original_goal");
  }

  /** True holds normal navigation while instructions execute; VERIFY resumes the normal planner. */
  public boolean tick(long now) {
    if (trial == null) return false;
    observe(now);
    if (trial == null) return true;
    if (revision != null) {
      try {
        var replacement = revision.tick(now, ruleActions);
        if (replacement != null) {
          program = replacement.program();
          executionContext = replacement.context();
          instructionMap = executionContext.map();
          revision = null;
          index = 0;
          stepStarted = nextAction = 0;
          deadline = now + 90_000;
          revisions++;
        }
      } catch (RuntimeException error) {
        finish(false, "recovery_revision_failed: " + error);
      }
      return true;
    }
    if (observing) return false;
    if (program == null) {
      if (!trial.program.isDone()) return true;
      try {
        program = trial.program.join();
        executionAt = now;
      } catch (Exception error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        finish(false, "proposal_rejected_or_unavailable: " + cause);
        return true;
      }
      deadline = now + 90_000;
    }
    if (now < nextAction) return true;
    SkillProgram.Step step = program.steps().get(index);
    if (step.op() == SkillProgram.Op.VERIFY) {
      observing = true;
      actor.getPathfinder().stopPathfinding();
      observe(now);
      return false;
    }
    if (step.op() == SkillProgram.Op.SEARCH || step.op() == SkillProgram.Op.TUNE) {
      try {
        ruleActions.execute(trial, step, executionContext.origin(), program.explanation());
        advance(now, step, executionContext.origin());
      } catch (IllegalArgumentException error) {
        revise("search_rule_rejected: " + error.getMessage());
      }
      return true;
    }
    Pos origin = executionContext.origin(), p;
    try {
      p =
          new Pos(
              Math.addExact(origin.x(), step.x()),
              Math.addExact(origin.y(), step.y()),
              Math.addExact(origin.z(), step.z()));
    } catch (ArithmeticException overflow) {
      finish(false, "proposed_coordinate_cannot_be_represented_in_world");
      return true;
    }
    if (stepStarted == 0) stepStarted = now;
    if (now - stepStarted > WorkerTuning.value(plugin, actor, "recovery.instruction_ms")) {
      revise("instruction_timeout: " + step.op());
      return true;
    }
    // Walk through successive locally observed maps; never reject a coordinate just because
    // it lies outside the immutable proposal context. Navigation can clear natural obstacles.
    if (step.op() == SkillProgram.Op.WALK) {
      if (here().x() == p.x() && here().z() == p.z() && Math.abs(here().y() - p.y()) <= 1) {
        advance(now, step, p);
      } else instructionNavigation.walkExact(p, 0, now);
      return true;
    }
    if (here().distance2(p) > 12 || !Bukkit.isOwnedByCurrentRegion(location(p), 3)) {
      instructionNavigation.walkExact(p, 12, now);
      return true;
    }
    if (!ensureObserved(p, now)) return true;
    if (step.op() == SkillProgram.Op.CLEAR && location(p).getBlock().getType().isAir()) {
      advance(now, step, p);
      return true;
    }
    if (step.op() == SkillProgram.Op.CLASSIFY) {
      try {
        ruleActions.execute(trial, step, p, program.explanation(), instructionMap);
        advance(now, step, p);
      } catch (IllegalArgumentException error) {
        revise("classification_probe_failed: " + error.getMessage());
      }
      return true;
    }
    if ((step.op() == SkillProgram.Op.CLEAR || step.op() == SkillProgram.Op.PLACE_SUPPORT)
        && !workPose.ready(location(p).getBlock(), now)) return true;
    if (step.op() == SkillProgram.Op.CLEAR) {
      if (instructionMap.cell(p).kind() != NavigationMap.Kind.SOFT
          && instructionMap.cell(p).kind() != NavigationMap.Kind.CLEARABLE
          && !BlockObservation.learnedClear(
              plugin.experiments().rules(), trial.worker, location(p).getBlock())) {
        revise("clear_target_not_observed_natural_material");
        return true;
      }
      var result =
          clearance.prepare(
              new TerrainRouteSearch.Step(p, List.of(p), List.of()),
              instructionMap,
              now,
              siteGoal ? trial.context.goal() : null);
      if (!result.failure().isEmpty()) {
        revise(result.failure());
        return true;
      }
      if (result.changed()) cleared++;
      if (result.ready() || result.changed()) {
        advance(now, step, p);
        return true;
      }
      return true;
    }
    String failure = place(p, step.material());
    if (failure != null) {
      revise(failure);
      return true;
    }
    placed++;
    advance(now, step, p);
    return true;
  }

  private boolean ensureObserved(Pos p, long now) {
    if (instructionMap != null
        && instructionMap.contains(p)
        && instructionMap.cell(p).kind() != NavigationMap.Kind.UNKNOWN) return true;
    if (observation == null) {
      if (now < nextObservation) return false;
      observation =
          plugin
              .navigation()
              .request(actor.getWorld(), village, trial.worker, p, p, 0, "recovery_observation");
      plugin.debug(
          village.id(),
          trial.worker,
          "recovery_map_extension",
          Map.of(
              "target",
              p,
              "origin",
              trial.context.origin(),
              "action",
              "observe next instruction area"));
    }
    if (!observation.isDone()) return false;
    try {
      instructionMap = observation.join().map();
    } catch (RuntimeException error) {
      plugin.debug(
          village.id(),
          trial.worker,
          "recovery_map_wait",
          Map.of("target", p, "reason", error.toString()));
    }
    observation = null;
    nextObservation = now + 3000;
    return instructionMap != null
        && instructionMap.contains(p)
        && instructionMap.cell(p).kind() != NavigationMap.Kind.UNKNOWN;
  }

  private String place(Pos p, String material) {
    Block b = location(p).getBlock();
    if (actor.getLocation().distanceSquared(location(p)) > 16) return "support_out_of_reach";
    if (!b.getType().isAir()
        || !b.getRelative(BlockFace.UP).isPassable()
        || !b.getRelative(0, 2, 0).isPassable()
        || !BlockRules.dry(b)) return "support_site_occupied_or_wet";
    if (village.gatherProtected(p) || plugin.playerProtected(village, p))
      return "support_site_reserved_or_protected";
    boolean anchored = false;
    for (BlockFace face :
        List.of(BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
      Block neighbor = b.getRelative(face);
      if (neighbor.getType().isSolid()
          && BlockRules.dry(neighbor)
          && !Set.of(Material.SAND, Material.RED_SAND, Material.GRAVEL)
              .contains(neighbor.getType())) anchored = true;
    }
    if (!anchored) return "support_has_no_dry_anchor";
    BoundingBox box = new BoundingBox(p.x(), p.y(), p.z(), p.x() + 1, p.y() + 1, p.z() + 1);
    if (actor.getWorld().getNearbyEntities(box).stream()
        .anyMatch(e -> e.getBoundingBox().overlaps(box))) return "support_would_intersect_entity";
    Material type = Material.valueOf(material);
    if (InventoryOps.count(actor.getInventory(), type) < 1)
      return "support_material_missing: " + material;
    if (!plugin.mayChange(actor, b, "PLACE")) return "support_placement_cancelled";
    // Event listeners can change the site or inventory before returning control.
    if (!b.getType().isAir()) return "support_site_changed_during_permission_event";
    if (InventoryOps.count(actor.getInventory(), type) < 1)
      return "support_material_changed_during_permission_event";
    b.setType(type, false);
    if (b.getType() != type) return "support_placement_not_persisted";
    InventoryOps.remove(actor.getInventory(), type, 1);
    actor.swingMainHand();
    return null;
  }

  private void advance(long now, SkillProgram.Step step, Pos position) {
    plugin
        .experiments()
        .step(
            trial,
            Map.of(
                "index",
                index,
                "instruction",
                step,
                "position",
                position,
                "actual_position",
                here(),
                "inventory",
                InventoryOps.summary(actor.getInventory()),
                "verified",
                true));
    workPose.reset();
    instructionNavigation.stop();
    instructionMap = null;
    observation = null;
    index++;
    instructionsCompleted++;
    stepStarted = 0;
    nextAction = now + WorkerTuning.value(plugin, actor, "construction.interval_ms");
  }

  private void revise(String reason) {
    if (revision != null || trial == null) return;
    if (program == null) {
      finish(false, reason);
      return;
    }
    instructionNavigation.stop();
    actor.getPathfinder().stopPathfinding();
    workPose.reset();
    observation = null;
    observing = false;
    deadline = System.currentTimeMillis() + 185_000;
    revision =
        new RecoveryRevision(plugin, actor, village, trial, program, index, reason, siteGoal);
  }

  private void finish(boolean success, String reason) {
    instructionNavigation.stop();
    observation = null;
    var done = trial;
    long finishedAt = System.currentTimeMillis();
    var finalInstruction =
        program == null || index >= program.steps().size() ? null : program.steps().get(index);
    trial = null;
    program = null;
    revision = null;
    actor.getPathfinder().stopPathfinding();
    Map<String, Object> evidence =
        new LinkedHashMap<>(
            Map.of(
                "reason",
                reason,
                "position",
                here(),
                "origin",
                done.context.origin(),
                "goal",
                done.context.goal(),
                "instructions_completed",
                instructionsCompleted,
                "placed",
                placed,
                "cleared",
                cleared,
                "inventory",
                InventoryOps.summary(actor.getInventory()),
                "basis",
                siteGoal
                    ? "physical site-clearance recovery; construction resumes separately"
                    : "physical navigation recovery outcome; not a completed village project"));
    evidence.put("elapsed_ms", Math.max(0, finishedAt - startedAt));
    evidence.put("execution_ms", executionAt == 0 ? 0 : Math.max(0, finishedAt - executionAt));
    evidence.put("distance_travelled", travelled);
    evidence.put("final_instruction", finalInstruction);
    evidence.put("revisions_executed", revisions);
    plugin.experiments().finish(done, success, reason, evidence);
    plugin.debug(
        village.id(),
        actor.getUniqueId().toString(),
        success ? "experiment_success" : "experiment_failure",
        evidence);
    completed.accept(success, reason);
  }

  private Pos here() {
    Location p = actor.getLocation();
    return new Pos(p.getBlockX(), p.getBlockY(), p.getBlockZ());
  }

  private Location location(Pos p) {
    return new Location(actor.getWorld(), p.x() + .5, p.y(), p.z() + .5);
  }
}
