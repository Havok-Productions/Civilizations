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
  private int index, placed, cleared;
  private long deadline, stepStarted, nextAction;
  private boolean observing, moving, siteGoal;

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
    ruleActions = new SkillRuleActions(plugin, actor);
    workPose = new WorkPose(plugin, actor);
  }

  public void failedVerification(String reason) {
    if (trial != null) finish(false, reason);
  }

  public String status() {
    return trial == null
        ? "idle"
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
    siteGoal = site;
    deadline = now + 185_000;
    workPose.reset();
    index = 0;
    placed = 0;
    cleared = 0;
    observing = false;
    moving = false;
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
      actor.getPathfinder().stopPathfinding();
    }
  }

  public void observe(long now) {
    if (trial == null) return;
    if (now > deadline) {
      finish(false, program == null ? "proposal_timeout" : "live_trial_deadline");
      return;
    }
    Pos at = here();
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
    if (observing) return false;
    if (program == null) {
      if (!trial.program.isDone()) return true;
      try {
        program = trial.program.join();
      } catch (Exception error) {
        finish(false, "proposal_rejected_or_unavailable: " + error.getClass().getSimpleName());
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
        ruleActions.execute(trial, step, trial.context.origin(), program.explanation());
        advance(now, step, trial.context.origin());
      } catch (IllegalArgumentException error) {
        finish(false, "search_rule_rejected: " + error.getMessage());
      }
      return true;
    }
    Pos origin = trial.context.origin(), p;
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
      finish(false, "instruction_timeout: " + step.op());
      return true;
    }
    if (!Bukkit.isOwnedByCurrentRegion(location(p), 3)) return true;
    if (!trial.context.map().contains(p)
        || trial.context.map().cell(p).kind() == NavigationMap.Kind.UNKNOWN) {
      finish(false, "instruction_outside_known_map");
      return true;
    }
    if (step.op() == SkillProgram.Op.CLASSIFY) {
      try {
        ruleActions.execute(trial, step, p, program.explanation());
        advance(now, step, p);
      } catch (IllegalArgumentException error) {
        finish(false, "classification_probe_failed: " + error.getMessage());
      }
      return true;
    }
    if (step.op() == SkillProgram.Op.WALK) {
      Pos at = here();
      if (at.x() == p.x() && at.z() == p.z() && Math.abs(at.y() - p.y()) <= 1) {
        advance(now, step, p);
        return true;
      }
      if (moving && actor.getPathfinder().hasPath()) return true;
      Block b = location(p).getBlock();
      if (!b.isPassable()
          || !b.getRelative(BlockFace.UP).isPassable()
          || !BlockRules.dry(b)
          || !b.getRelative(BlockFace.DOWN).getType().isSolid()) {
        finish(false, "walk_target_not_dry_supported_space");
        return true;
      }
      var path = actor.getPathfinder().findPath(location(p));
      if (path == null
          || path.getFinalPoint() == null
          || path.getFinalPoint().distanceSquared(location(p)) > 2
          || !safePath(path)) {
        finish(false, "native_skill_path_unavailable_or_unsafe");
        return true;
      }
      if (!actor.getPathfinder().moveTo(path, plugin.speed())) {
        finish(false, "native_skill_move_rejected");
        return true;
      }
      moving = true;
      return true;
    }
    if ((step.op() == SkillProgram.Op.CLEAR || step.op() == SkillProgram.Op.PLACE_SUPPORT)
        && !workPose.ready(location(p).getBlock(), now)) return true;
    if (step.op() == SkillProgram.Op.CLEAR) {
      if (trial.context.map().cell(p).kind() != NavigationMap.Kind.SOFT
          && trial.context.map().cell(p).kind() != NavigationMap.Kind.CLEARABLE
          && !BlockObservation.learnedClear(
              plugin.experiments().rules(), trial.worker, location(p).getBlock())) {
        finish(false, "clear_target_not_observed_natural_material");
        return true;
      }
      var result =
          clearance.prepare(
              new TerrainRouteSearch.Step(p, List.of(p), List.of()),
              trial.context.map(),
              now,
              siteGoal ? trial.context.goal() : null);
      if (!result.failure().isEmpty()) {
        finish(false, result.failure());
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
      finish(false, failure);
      return true;
    }
    placed++;
    advance(now, step, p);
    return true;
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

  private boolean safePath(com.destroystokyo.paper.entity.Pathfinder.PathResult path) {
    boolean exiting = actor.getLocation().getBlock().getType() == Material.WATER;
    int water = 0;
    for (Location node : path.getPoints()) {
      if (!Bukkit.isOwnedByCurrentRegion(node, 2)) return false;
      Block feet = node.getBlock(), head = feet.getRelative(BlockFace.UP);
      if (feet.getType() == Material.WATER && exiting && ++water <= 3 && head.getType().isAir())
        continue;
      exiting = false;
      if (!BlockRules.dry(feet)) return false;
      for (Block b : List.of(feet, head, feet.getRelative(BlockFace.DOWN)))
        if (Set.of(
                "FIRE",
                "SOUL_FIRE",
                "MAGMA_BLOCK",
                "TNT",
                "WITHER_ROSE",
                "CACTUS",
                "CAMPFIRE",
                "SOUL_CAMPFIRE",
                "POWDER_SNOW",
                "SWEET_BERRY_BUSH",
                "COBWEB")
            .contains(b.getType().name())) return false;
    }
    return true;
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
    index++;
    stepStarted = 0;
    moving = false;
    nextAction = now + WorkerTuning.value(plugin, actor, "construction.interval_ms");
  }

  private void finish(boolean success, String reason) {
    var done = trial;
    trial = null;
    program = null;
    actor.getPathfinder().stopPathfinding();
    Map<String, Object> evidence =
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
            index,
            "placed",
            placed,
            "cleared",
            cleared,
            "inventory",
            InventoryOps.summary(actor.getInventory()),
            "basis",
            siteGoal
                ? "physical site-clearance recovery; construction resumes separately"
                : "physical navigation recovery outcome; not a completed village project");
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
