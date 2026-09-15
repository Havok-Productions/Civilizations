package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import dev.civilizations.learning.*;
import java.util.function.BiConsumer;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.Gate;
import org.bukkit.entity.Villager;

/** Native-speed movement and route-stall detection, exclusively on the entity scheduler. */
public final class WorkerNavigation {
  private final CivilizationsPlugin plugin;
  private final Villager actor;
  private final Settlement village;
  private final BiConsumer<Long, String> failed;
  private Pos lastDestination;
  private long nextMove;
  private long progressAt;

  public long progressAt() {
    return progressAt;
  }

  private String reportState = "no route requested";
  private CoreAiCoordinator.Ticket policyTicket;
  private final WorkerSkillTrial skillTrial;

  private long nextSiteTrial;

  public boolean recoverSite(Pos target, long now) {
    if (skillTrial == null) return false;
    if (experimentActive() || pending) return true;
    if (now < nextSiteTrial) return false;
    nextSiteTrial = now + 30_000;
    pending = true;
    int token = ++generation;
    plugin
        .navigation()
        .request(actor.getWorld(), village, actor.getUniqueId().toString(), here(), target, 2)
        .whenComplete(
            (answer, error) ->
                actor
                    .getScheduler()
                    .run(
                        plugin,
                        t -> {
                          if (token != generation) return;
                          pending = false;
                          if (error == null
                              && skillTrial.startSite(
                                  answer.map(),
                                  target,
                                  "Build site contains unclassified nonstructural clutter",
                                  System.currentTimeMillis())) {
                            plan = null;
                            selected = null;
                            return;
                          }
                          failed.accept(
                              System.currentTimeMillis(),
                              error == null
                                  ? "Site classification trial unavailable"
                                  : "Site observation failed: " + error);
                        },
                        () -> pending = false));
    return true;
  }

  public boolean experimentTick(long now) {
    return skillTrial != null && skillTrial.tick(now);
  }

  public void damage(String reason) {
    if (skillTrial != null) skillTrial.failedVerification("damage_event: " + reason);
  }

  public boolean experimentActive() {
    return skillTrial != null && skillTrial.active();
  }

  public String status() {
    return experimentActive()
        ? skillTrial.status()
        : pending ? "waiting for terrain snapshot" : reportState;
  }

  public WorkerNavigation(
      CivilizationsPlugin plugin,
      Villager actor,
      Settlement village,
      BiConsumer<Long, String> failed) {
    this(plugin, actor, village, failed, true);
  }

  WorkerNavigation(
      CivilizationsPlugin plugin,
      Villager actor,
      Settlement village,
      BiConsumer<Long, String> failed,
      boolean trials) {
    this.plugin = plugin;
    this.actor = actor;
    this.village = village;
    this.failed = failed;
    this.clearance = new RouteClearance(plugin, actor, village);
    this.observation = new NavigationObservation(actor);
    skillTrial =
        !trials
            ? null
            : new WorkerSkillTrial(
                plugin,
                actor,
                village,
                (success, reason) -> {
                  plan = null;
                  selected = null;
                  nextPlan = 0;
                  recoveryAttempts = 0;
                  requestStarted = 0;
                  if (success) {
                    if (lastDestination != null)
                      village.knowledge().clear("route:" + lastDestination.key());
                  } else
                    failed.accept(System.currentTimeMillis(), "Live recovery failed: " + reason);
                });
  }

  private Location location(Pos p) {
    return new Location(actor.getWorld(), p.x() + .5, p.y(), p.z() + .5);
  }

  private Pos here() {
    Location p = actor.getLocation();
    return new Pos(p.getBlockX(), p.getBlockY(), p.getBlockZ());
  }

  public void stop() {
    if (skillTrial != null) skillTrial.cancel("navigation_cancelled_or_target_changed");
    generation++;
    pending = false;
    plan = null;
    selected = null;
    index = 0;
    policyTicket = null;
    actor.getPathfinder().stopPathfinding();
    lastDestination = null;
    requestStarted = 0;
    clearanceStarted = 0;
    recoveryAttempts = 0;
  }

  public void walk(Pos destination, long now) {
    walk(destination, destination, 12, now);
  }

  public void walkExact(Pos target, int reach, long now) {
    walk(target, target, reach, now);
  }

  public dev.civilizations.navigation.NavigationMap observedMap() {
    return observedMap;
  }

  public void walkWork(Pos stand, Pos target, long now) {
    if (!target.equals(workTarget)) {
      rejectedWorkPositions.clear();
      workTarget = target;
      workApproach = null;
    }
    int reach = WorkerTuning.value(plugin, actor, "construction.reach_squared");
    if (workApproach != null
        && Bukkit.isOwnedByCurrentRegion(location(target), 1)
        && Bukkit.isOwnedByCurrentRegion(location(workApproach), 1)
        && !WorkPositions.usable(actor, workApproach, target, reach)) {
      rejectedWorkPositions.add(workApproach);
      event(
          "work_position_occluded_or_changed",
          java.util.Map.of(
              "stand",
              workApproach,
              "target",
              target,
              "next_action",
              "retain another approach until arrival; do not reselect this failed stand"),
          true);
      workApproach = null;
    }
    if (workApproach == null || rejectedWorkPositions.contains(workApproach))
      workApproach = WorkPositions.choose(actor, stand, target, reach, rejectedWorkPositions);
    if (workApproach == null) {
      event(
          "no_visible_supported_work_position",
          java.util.Map.of(
              "target",
              target,
              "rejected_stands",
              java.util.Set.copyOf(rejectedWorkPositions),
              "next_action",
              "retain task and request recovery observations"),
          true);
      failed.accept(
          now,
          "No observed supported work position can see target "
              + target.key()
              + "; preparation or scaffolding needed");
      return;
    }
    // Ranking again on every movement tick makes a corner's center-visible cell win as soon as
    // the actor leaves it, cancelling the longer route to a genuinely visible working position.
    walk(workApproach, target, reach, now);
  }

  private Pos workTarget;
  private Pos workApproach;
  private final java.util.Set<Pos> rejectedWorkPositions = new java.util.HashSet<>();

  private NavigationService.Plan plan;
  private final RouteClearance clearance;
  private final NavigationObservation observation;
  private int generation, index, selectedIndex;
  private volatile boolean pending;
  private Pos plannedTarget, selected;
  private long nextPlan, selectedAt;
  private long requestStarted, clearanceStarted;
  private int recoveryAttempts;
  private dev.civilizations.navigation.NavigationMap observedMap;
  private long nextFailureMap;

  public void mapFailure(String failureId, Pos target, long now) {
    if (now < nextFailureMap) {
      plugin.debug(
          village.id(),
          actor.getUniqueId().toString(),
          "failure_map",
          java.util.Map.of(
              "failure_id",
              failureId,
              "status",
              "capture_throttled",
              "retry_after_ms",
              nextFailureMap - now,
              "last_navigation",
              lastEvidence));
      return;
    }
    nextFailureMap = now + 10_000;
    String worker = actor.getUniqueId().toString();
    plugin
        .navigation()
        .request(actor.getWorld(), village, worker, here(), target, 2)
        .whenComplete(
            (answer, error) -> {
              if (error != null)
                plugin.debug(
                    village.id(),
                    worker,
                    "failure_map",
                    java.util.Map.of("failure_id", failureId, "error", error.toString()));
              else
                plugin.debug(
                    village.id(),
                    worker,
                    "failure_map",
                    java.util.Map.of(
                        "failure_id",
                        failureId,
                        "map_id",
                        answer.id(),
                        "map_file",
                        answer.file(),
                        "target",
                        target,
                        "route_reason",
                        answer.route().reason(),
                        "rejections",
                        answer.route().rejected()));
            });
  }

  public java.util.List<Pos> observed(String material) {
    return observedMap == null ? java.util.List.of() : observedMap.positions(material);
  }

  private java.util.Map<String, Object> lastEvidence = java.util.Map.of("state", "not_started");

  public java.util.Map<String, Object> evidence() {
    return lastEvidence;
  }

  private void event(String reason, java.util.Map<String, ?> details, boolean failure) {
    java.util.Map<String, Object> value = new java.util.LinkedHashMap<>(details);
    value.put("reason", reason);
    value.put("position", here());
    if (plan != null) {
      value.put("map_id", plan.id());
      value.put("map_file", plan.file());
      value.put("terrain_fingerprint", plan.map().fingerprint);
      value.put("route_index", index);
      value.put("route_size", plan.route().steps().size());
      value.put("map_age_ms", Math.max(0, System.currentTimeMillis() - plan.capturedAt()));
    }
    lastEvidence = java.util.Map.copyOf(value);
    reportState = reason;
    plugin.debug(
        village.id(),
        actor.getUniqueId().toString(),
        failure ? "navigation_failure" : "navigation",
        lastEvidence);
  }

  private void walk(Pos destination, Pos target, int range, long now) {
    if (now < nextMove) return;
    nextMove = now + 500;
    Pos at = here();
    if (!destination.equals(lastDestination) || !target.equals(plannedTarget)) {
      stop();
      lastDestination = destination;
      plannedTarget = target;
      nextPlan = 0;
    }
    if (experimentActive()) requestStarted = now;
    if (requestStarted == 0) requestStarted = now;
    if (now - requestStarted > 90_000) {
      event(
          "navigation_task_deadline",
          java.util.Map.of("target", target, "elapsed_ms", now - requestStarted),
          true);
      failed.accept(now, "Navigation could not make progress within 90 seconds");
      return;
    }
    if (plan != null && index < plan.route().steps().size()) {
      var changes =
          dev.civilizations.navigation.RouteChanges.inspect(
              plan.map(),
              plan.route().steps().subList(index, Math.min(index + 4, plan.route().steps().size())),
              observation::cell);
      if (!changes.isEmpty()) {
        refresh(now, "route_terrain_changed", java.util.Map.of("changes", changes));
        return;
      }
    }
    if (selected != null) {
      if (at.x() == selected.x()
          && at.z() == selected.z()
          && Math.abs(at.y() - selected.y()) <= 1) {
        if (policyTicket != null)
          plugin
              .coreAi()
              .outcome(
                  policyTicket,
                  true,
                  java.util.Map.of(
                      "to",
                      at,
                      "planned_step",
                      selected,
                      "executor_result",
                      "reached planned terrain-route step"));
        policyTicket = null;
        index = selectedIndex + 1;
        progressAt = now;
        selected = null;
        requestStarted = now;
        clearanceStarted = 0;
      } else if (now - selectedAt > WorkerTuning.value(plugin, actor, "navigation.transition_ms")) {
        rejectStep(
            now,
            "native_movement_stalled",
            nativeDetails(selected, java.util.Map.of("wait_ms", now - selectedAt)));
        return;
      } else if (actor.getPathfinder().hasPath()) return;
    }
    if (plan == null) {
      if (pending || now < nextPlan) return;
      pending = true;
      int token = ++generation;
      plugin
          .navigation()
          .request(
              actor.getWorld(),
              village,
              actor.getUniqueId().toString(),
              at,
              destination,
              destination.equals(target) ? range : 0)
          .whenComplete(
              (answer, error) ->
                  actor
                      .getScheduler()
                      .run(
                          plugin,
                          t -> {
                            if (token != generation) return;
                            pending = false;
                            if (error != null) {
                              if (experimentActive())
                                skillTrial.failedVerification(
                                    "map_execution_failed: " + error.getMessage());
                              nextPlan = System.currentTimeMillis() + 3000;
                              event(
                                  "map_capture_or_search_failed",
                                  java.util.Map.of("error", error.toString()),
                                  true);
                              return;
                            }
                            plan = answer;
                            observedMap = answer.map();
                            index = 0;
                            selected = null;
                            event(
                                answer.route().reason(),
                                java.util.Map.of(
                                    "target",
                                    target,
                                    "radius",
                                    answer.map().radius,
                                    "expanded",
                                    answer.route().expanded(),
                                    "rejections",
                                    answer.route().rejected(),
                                    "examples",
                                    answer.route().examples(),
                                    "reached",
                                    answer.route().reached(),
                                    "remembered_transitions",
                                    answer.remembered().stream()
                                        .map(
                                            v ->
                                                java.util.Map.of(
                                                    "edge",
                                                    v.edge(),
                                                    "worker",
                                                    v.worker(),
                                                    "reason",
                                                    v.reason(),
                                                    "observed_at",
                                                    v.observedAt(),
                                                    "retry_at",
                                                    v.until()))
                                        .toList()),
                                answer.route().steps().isEmpty() && !answer.route().reached());
                            if (answer.route().steps().isEmpty() && !answer.route().reached()) {
                              if (!destination.equals(target)
                                  && target.equals(workTarget)
                                  && rejectedWorkPositions.add(destination)) {
                                event(
                                    "work_position_unreachable",
                                    java.util.Map.of(
                                        "stand",
                                        destination,
                                        "target",
                                        target,
                                        "route_reason",
                                        answer.route().reason(),
                                        "next_action",
                                        "try another observed work position"),
                                    true);
                                plan = null;
                                selected = null;
                                nextPlan = 0;
                                requestStarted = 0;
                                return;
                              }
                              if (skillTrial != null
                                  && !experimentActive()
                                  && skillTrial.start(
                                      answer.map(),
                                      target,
                                      range,
                                      answer.route().reason(),
                                      System.currentTimeMillis())) {
                                plan = null;
                                selected = null;
                                nextPlan = 0;
                                return;
                              }
                              if (experimentActive()) {
                                skillTrial.failedVerification(
                                    "route_still_blocked: " + answer.route().reason());
                                return;
                              }
                              nextPlan = System.currentTimeMillis() + 30_000;
                              village
                                  .knowledge()
                                  .block(
                                      "route:" + destination.key(),
                                      answer.route().reason(),
                                      System.currentTimeMillis(),
                                      30_000);
                              failed.accept(
                                  System.currentTimeMillis(),
                                  "Mapped route failed: " + answer.route().reason());
                            }
                          },
                          () -> pending = false));
      return;
    }
    if (index >= plan.route().steps().size()) {
      boolean arrived = plan.route().reached();
      plan = null;
      selected = null;
      nextPlan = now + (arrived ? 1500 : 0);
      return;
    }
    var step = plan.route().steps().get(index);
    if (clearanceStarted == 0) clearanceStarted = now;
    var prepared = clearance.prepare(step, plan.map(), now);
    if (prepared.changed()) {
      progressAt = now;
      requestStarted = now;
    }
    if (!prepared.failure().isEmpty()) {
      if (prepared.failure().contains("changed"))
        refresh(now, prepared.failure(), prepared.details());
      else rejectStep(now, prepared.failure(), prepared.details());
      return;
    }
    if (!prepared.ready()) {
      if (now - clearanceStarted > 15_000)
        rejectStep(
            now,
            "obstacle_action_wait_timeout",
            java.util.Map.of(
                "step",
                step,
                "wait_ms",
                now - clearanceStarted,
                "blocked_action",
                prepared.details()));
      return;
    }
    // Rank only successive nodes from an already connected path. Never skip an obstacle action.
    java.util.List<Pos> candidates = new java.util.ArrayList<>();
    for (int i = index; i < Math.min(index + 4, plan.route().steps().size()); i++) {
      var candidate = plan.route().steps().get(i);
      if (i > index && !candidate.clear().isEmpty()) break;
      if (i > index && !candidate.open().isEmpty()) {
        // Prepare consecutive reachable doorway cells before choosing a landing beyond them.
        var doorway = clearance.prepare(candidate, plan.map(), now);
        if (!doorway.ready()) break;
      }
      candidates.add(candidate.feet());
    }
    // A doorway is an action location, often a poor native landing. Prefer a clear node beyond it.
    java.util.Collections.reverse(candidates);
    CoreAiCoordinator.Choice choice =
        plugin.coreAi() == null
            ? null
            : plugin
                .coreAi()
                .rank(
                    CoreAiCoordinator.Scope.ROUTES,
                    VillagerPolicies.routes(candidates, at, destination),
                    experimentActive() ? null : actor.getUniqueId().toString());
    if (choice != null) candidates = VillagerPolicies.ordered(candidates, choice, Pos::key);
    java.util.List<java.util.Map<String, Object>> attempts = new java.util.ArrayList<>();
    int nativeAttempts = 0;
    for (Pos candidate : candidates) {
      if (!Bukkit.isOwnedByCurrentRegion(location(candidate), 1)) {
        event(
            "native_candidate_region_not_owned",
            java.util.Map.of(
                "candidate", candidate, "next_action", "wait for owning-region observation"),
            true);
        continue;
      }
      Block feet = location(candidate).getBlock();
      if (feet.isLiquid() || feet.getRelative(BlockFace.UP).isLiquid()) {
        refresh(
            now, "native_candidate_became_liquid", nativeDetails(candidate, java.util.Map.of()));
        return;
      }
      nativeAttempts++;
      var path = actor.getPathfinder().findPath(location(candidate));
      if (path == null || path.getFinalPoint() == null) {
        attempts.add(java.util.Map.of("candidate", candidate, "reason", "native_path_missing"));
        event(
            "native_path_missing",
            nativeDetails(candidate, java.util.Map.of("path_null", path == null)),
            true);
        continue;
      }
      Location endLocation = path.getFinalPoint();
      Pos end = new Pos(endLocation.getBlockX(), endLocation.getBlockY(), endLocation.getBlockZ());
      if (end.distance2(candidate) > 2) {
        event(
            "native_path_endpoint_mismatch",
            nativeDetails(
                candidate,
                java.util.Map.of(
                    "endpoint", end, "endpoint_distance_squared", end.distance2(candidate))),
            true);
        attempts.add(
            java.util.Map.of(
                "candidate",
                candidate,
                "reason",
                "native_path_endpoint_mismatch",
                "endpoint",
                end));
        continue;
      }
      var pathFailure = observation.pathFailure(path);
      if (!pathFailure.isEmpty()) {
        event(
            "native_path_contains_hazard_or_unowned_region",
            nativeDetails(candidate, java.util.Map.of("rejected_node", pathFailure)),
            true);
        attempts.add(
            java.util.Map.of(
                "candidate",
                candidate,
                "reason",
                "native_path_unsafe",
                "rejected_node",
                pathFailure));
        continue;
      }
      if (!actor.getPathfinder().moveTo(path, plugin.speed())) {
        event(
            "native_move_rejected",
            nativeDetails(candidate, java.util.Map.of("endpoint", end)),
            true);
        attempts.add(java.util.Map.of("candidate", candidate, "reason", "native_move_rejected"));
        continue;
      }
      if (selected == null) selectedAt = now;
      selected = candidate;
      for (int i = index; i < Math.min(index + 4, plan.route().steps().size()); i++)
        if (plan.route().steps().get(i).feet().equals(candidate)) {
          selectedIndex = i;
          break;
        }
      if (choice != null && policyTicket == null)
        policyTicket =
            plugin
                .coreAi()
                .begin(village.id(), actor.getUniqueId().toString(), choice, candidate.key());
      village.knowledge().clear("route:" + destination.key());
      event(
          "following_mapped_route",
          java.util.Map.of("goal", target, "next_step", candidate, "native_endpoint", end),
          false);
      return;
    }
    rejectStep(
        now,
        "all_native_transitions_rejected",
        java.util.Map.of(
            "attempted", candidates, "results", attempts, "native_attempts", nativeAttempts));
  }

  private java.util.Map<String, Object> nativeDetails(
      Pos candidate, java.util.Map<String, ?> extra) {
    var details = observation.failure(candidate, plan == null ? null : plan.map());
    details.putAll(extra);
    return details;
  }

  private void refresh(long now, String reason, java.util.Map<String, ?> details) {
    var value = new java.util.LinkedHashMap<String, Object>(details);
    value.put("next_action", "fresh terrain map; detour or prepare observed natural obstruction");
    value.put("remembered_as_blocked", false);
    event(reason, value, false);
    if (policyTicket != null) plugin.coreAi().outcome(policyTicket, false, lastEvidence);
    policyTicket = null;
    actor.getPathfinder().stopPathfinding();
    plan = null;
    selected = null;
    clearanceStarted = 0;
    rejectedWorkPositions.clear();
    workApproach = null;
    nextPlan = now;
  }

  private void rejectStep(long now, String reason, java.util.Map<String, ?> details) {
    var value = new java.util.LinkedHashMap<String, Object>(details);
    value.put("remembered_as_blocked", false);
    if (plan != null && index < plan.route().steps().size()) {
      var step = plan.route().steps().get(index);
      value.put("step", step);
      value.put("live_step", observation.block(step.feet(), plan.map()));
      Pos from =
          index == 0
              ? dev.civilizations.navigation.TerrainRouteSearch.start(plan.map(), plan.map().center)
              : plan.route().steps().get(index - 1).feet();
      // Resource/protection/region waits are not failed terrain edges. Nor is a stall halfway
      // along a native path evidence that its first edge was blocked.
      boolean nativeFailure =
          reason.equals("native_movement_stalled")
              || reason.equals("all_native_transitions_rejected")
                  && details.get("native_attempts") instanceof Integer count
                  && count > 0;
      if (nativeFailure && here().equals(from)) {
        var remembered =
            plugin
                .navigation()
                .reject(
                    village.id(),
                    actor.getUniqueId().toString(),
                    new dev.civilizations.navigation.TerrainRouteSearch.Edge(from, step.feet()),
                    plan.map(),
                    reason,
                    now,
                    WorkerTuning.value(plugin, actor, "navigation.retry_ms"));
        value.put("remembered_as_blocked", true);
        value.put("memory_scope", "this_worker_only");
        value.put("retry_at", remembered.until());
        value.put("retry_after_ms", Math.max(0, remembered.until() - now));
      }
    }
    value.put(
        "next_action", "fresh map and alternate route; changed geometry invalidates retry memory");
    event(reason, value, true);
    if (policyTicket != null) plugin.coreAi().outcome(policyTicket, false, lastEvidence);
    policyTicket = null;
    actor.getPathfinder().stopPathfinding();
    plan = null;
    selected = null;
    nextPlan = now + 1500;
    clearanceStarted = 0;
    if (++recoveryAttempts >= WorkerTuning.value(plugin, actor, "navigation.recovery_attempts")) {
      if (skillTrial != null
          && !experimentActive()
          && observedMap != null
          && skillTrial.start(observedMap, plannedTarget, 12, reason, now)) return;
      if (experimentActive()) {
        skillTrial.failedVerification(reason);
        return;
      }
      village.knowledge().block("route:" + lastDestination.key(), reason, now, 60_000);
      failed.accept(
          now, "Navigation exhausted " + recoveryAttempts + " recovery attempts: " + reason);
    }
  }

  public void gates() {
    Pos p = here();
    if (!Bukkit.isOwnedByCurrentRegion(actor.getLocation(), 1)) return;
    for (int dx = -2; dx <= 2; dx++)
      for (int dz = -2; dz <= 2; dz++)
        for (int dy = -1; dy <= 1; dy++) {
          Pos q = p.add(dx, dy, dz);
          Block b = location(q).getBlock();
          if (b.getBlockData() instanceof Gate gate && !gate.isOpen()) {
            if (!plugin.mayChange(actor, b, "OPEN_GATE")) continue;
            gate.setOpen(true);
            b.setBlockData(gate, false);
            Bukkit.getRegionScheduler()
                .runDelayed(
                    plugin,
                    b.getLocation(),
                    task -> {
                      if (b.getBlockData() instanceof Gate g) {
                        g.setOpen(false);
                        b.setBlockData(g, false);
                      }
                    },
                    40);
          }
        }
  }
}
