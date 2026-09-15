package dev.civilizations.world;

import com.google.gson.Gson;
import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.ai.Decision;
import dev.civilizations.ai.ReasoningMode;
import dev.civilizations.core.*;
import dev.civilizations.learning.*;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.*;

/** Mutable worker state is exclusively owned by the villager's EntityScheduler. */
public final class VillagerWorker {
  private final CivilizationsPlugin plugin;
  private final Villager entity;
  private final Settlement village;
  private final String id;
  private final Random random = new Random();
  private ScheduledTask scheduled;
  private Job job, supplyFor;
  private final WorkerNavigation navigation;
  private final WorkMovementControl movementControl;
  private final BuildingActions building;
  private final WorkPose workPose;
  private final VillagerMind mind;
  private String waitingReason = "";
  private final GatheringActions gathering;
  private final RecoveryPolicy recovery;
  private final ToolActions tools;
  private final NearbyWork nearby;
  private final DeliveryActions deliveries;
  private Map<String, Object> lastFailure = Map.of();
  private Map<String, Integer> requests = Map.of();
  private String lastStep = "";

  public String villageId() {
    return village.id();
  }

  public Map<String, Integer> requests() {
    return requests;
  }

  private Map<String, Integer> needed(Job candidate) {
    return tools.needed(candidate, inventory(), here(), System.currentTimeMillis());
  }

  private void debug(String type, Map<String, ?> data) {
    plugin.debug(village.id(), id, type, data);
  }

  private boolean awaiting;
  private Decision nextAdvice;
  private long responseDeadline;
  private String mode = "idle", resource = "COBBLESTONE";
  private long nextThink, nextWork, nextStock;
  private long generation;
  private volatile boolean stopped;
  private boolean night, fleeing;
  private volatile String display = "starting";

  public VillagerWorker(CivilizationsPlugin plugin, Villager entity, Settlement village) {
    this.plugin = plugin;
    this.entity = entity;
    this.village = village;
    this.id = entity.getUniqueId().toString();
    mind =
        new VillagerMind(
            id,
            village,
            experience -> {
              debug("agent_experience", Map.of("experience", experience));
              if (plugin.coreAi() != null) plugin.coreAi().experience(experience);
            },
            message -> plugin.getLogger().warning(message));
    village.observe((worker, event) -> plugin.debug(village.id(), worker, "result", event));
    movementControl =
        new WorkMovementControl(
            entity,
            message -> {
              plugin.getLogger().warning(message);
              debug("movement_adapter_failure", Map.of("reason", message));
            });
    nearby = new NearbyWork(plugin, entity, village);
    workPose = new WorkPose(plugin, entity);
    building = new BuildingActions(plugin, entity, this::fail, this::complete);
    navigation = new WorkerNavigation(plugin, entity, village, this::fail);
    deliveries = new DeliveryActions(plugin, entity, village, navigation);
    recovery = new RecoveryPolicy(System.currentTimeMillis(), plugin.reasoningCooldown());
    gathering = new GatheringActions(plugin, entity, village, navigation, recovery, this::fail);
    tools = new ToolActions(plugin, entity, village, navigation, this::fail);
  }

  public void start() {
    // The Consumer is the work callback. The retired Runnable does only bookkeeping.
    scheduled =
        entity
            .getScheduler()
            .runAtFixedRate(
                plugin,
                t -> tick(),
                () -> {
                  stopped = true;
                  mind.close();
                  if (plugin.experiments() != null)
                    plugin.experiments().cancelWorker(id, "worker_retired");
                  village.release(id);
                  deliveries.cancel();
                  plugin.retired(id, this);
                },
                1,
                5);
    if (scheduled == null) {
      stopped = true;
      plugin.retired(id, this);
    }
  }

  public void stop() {
    stopped = true;
    mind.close();
    if (plugin.experiments() != null) plugin.experiments().cancelWorker(id, "worker_stopped");
    if (scheduled != null) scheduled.cancel();
    village.release(id);
    deliveries.cancel();
    if (Bukkit.isOwnedByCurrentRegion(entity)) {
      navigation.stop();
      movementControl.working(false);
    } else if (plugin.isEnabled())
      entity
          .getScheduler()
          .run(
              plugin,
              t -> {
                navigation.stop();
                movementControl.working(false);
              },
              () -> {});
  }

  public String status() {
    var progress = village.knowledge().worker(id);
    return display
        + (progress == null
            ? ""
            : " | goal="
                + progress.goal()
                + " | step="
                + progress.step()
                + " | blocked="
                + progress.blocker()
                + " | last result="
                + progress.lastResult())
        + " | model="
        + village.suggestion(id);
  }

  /** Called on the entity's owning region; this is observed state, not private model reasoning. */
  public List<String> inspection() {
    return List.of(
        "Worker " + id + " | village=" + village.id(),
        "State: " + status(),
        mind.status(),
        "Position: " + here().key() + " | inventory: " + inventory(),
        "Task: "
            + (job == null
                ? "none currently claimed"
                : job.kind + " " + job.project + " at " + job.target.key()),
        "Requested supplies: " + requests,
        "Navigation: "
            + navigation.status()
            + " | plugin movement control="
            + movementControl.controlling(),
        "Last failure: " + lastFailure.getOrDefault("reason", "none recorded"));
  }

  public Map<String, Object> navigationEvidence() {
    return navigation.evidence();
  }

  public List<dev.coreai.agent.AgentSession.Experience> agentExperiences() {
    return mind.experiences();
  }

  public void damaged(String cause) {
    navigation.damage(cause);
  }

  public String id() {
    return id;
  }

  private Pos here() {
    Location p = entity.getLocation();
    return new Pos(p.getBlockX(), p.getBlockY(), p.getBlockZ());
  }

  private Location location(Pos p) {
    return new Location(entity.getWorld(), p.x() + 0.5, p.y(), p.z() + 0.5);
  }

  private boolean owns(Pos p, int chunks) {
    return Bukkit.isOwnedByCurrentRegion(location(p), chunks);
  }

  public boolean retiredVillage() {
    return village.retired();
  }

  private void tick() {
    if (stopped) return;
    if (village.retired()) {
      plugin.reconnect(entity);
      return;
    }
    plugin
        .connections()
        .read(
            () -> {
              if (!village.retired()) tickActive();
            });
  }

  private void tickActive() {
    if (stopped || !entity.isValid() || entity.isDead()) return;
    try {
      long now = System.currentTimeMillis();
      Pos at = here();
      if (awaiting && now >= responseDeadline) {
        awaiting = false;
        generation++;
        debug(
            "decision_expired",
            Map.of("result", "Continuing validated work; stale answer ignored"));
      }
      if (village.paused()) {
        deliveries.cancel();
        navigation.stop();
        movementControl.working(false);
        entity.getPathfinder().stopPathfinding();
        recovery.pause(now);
        display = "paused";
        return;
      }
      if (threatened()) {
        movementControl.working(false);
        // Cancel our work once; repeatedly stopping the pathfinder would cancel vanilla fleeing.
        if (!fleeing) reset();
        fleeing = true;
        village.needs().threat(now);
        recovery.pause(now);
        display = "fleeing danger";
        return;
      }
      fleeing = false;
      long time = entity.getWorld().getTime();
      if (time >= 12500 && time < 23500) {
        movementControl.working(false);
        if (!night) {
          reset();
          night = true;
        }
        recovery.pause(now);
        sleep(now, at);
        return;
      }
      if (night) {
        night = false;
        if (entity.isSleeping()) entity.wakeup();
        nextThink = 0;
      }
      if (now >= nextStock) {
        nextStock = now + 5000;
        for (Pos chest : village.chests())
          if (at.distance2(chest) < 25
              && owns(chest, 1)
              && location(chest).getBlock().getState() instanceof Chest c)
            village.stock(chest, InventoryOps.summary(c.getInventory()), now);
      }
      requests = currentRequests();
      deliveries.publish(at, job, supplyFor, requests, now);
      nearby.tick(requests, now);
      if (job != null && !village.renew(job.id, id, now)
          || supplyFor != null && !village.renew(supplyFor.id, id, now)) reset();
      if (!navigation.experimentActive() && deliveries.tick(now)) {
        movementControl.working(true);
        recovery.pause(now);
        display = deliveries.status();
        return;
      }
      var incoming = village.deliveries().incoming(id, now);
      if (incoming != null && !navigation.experimentActive()) {
        navigation.stop();
        movementControl.working(true);
        recovery.pause(now);
        display = "awaiting courier: " + incoming.material();
        return;
      }
      var progress = village.knowledge().worker(id);
      String step =
          mode
              + "|"
              + (job == null ? "" : job.id)
              + "|"
              + (progress == null ? "" : progress.step());
      if (!step.equals(lastStep)) {
        lastStep = step;
        debug(
            "step",
            Map.of(
                "mode",
                mode,
                "job",
                job == null ? "" : job.id,
                "position",
                at,
                "inventory",
                inventory(),
                "requested",
                requests,
                "step",
                progress == null ? "" : progress.step()));
      }
      navigation.gates();
      if (navigation.experimentActive()) recovery.pause(now);
      if (now - navigation.progressAt() < 1000) recovery.pause(now);
      if (!awaiting && job != null && plugin.thinkingWhenStuck() && recovery.stalled(now)) {
        fail(now, recovery.problem(now));
        recovery.observedStall(now);
        nextWork = now;
        mode = "idle";
      }
      if (mode.equals("idle")) choose(now, at);
      movementControl.working(Set.of("work", "gather", "deposit").contains(mode));
      if (navigation.experimentTick(now)) {
        display = navigation.status();
        return;
      }
      display =
          (mode + (awaiting ? " (model considering next step)" : ""))
              + (job == null ? "" : " " + job.project)
              + (mode.equals("gather") ? " " + resource : "");
      switch (mode) {
        case "work" -> work(now, at);
        case "gather" -> gather(now, at);
        case "deposit" -> deposit(now, at);
        case "rest" -> {
          if (now >= nextWork) mode = "idle";
        }
        default -> {}
      }
    } catch (Exception e) {
      display = "recovering: " + e.getClass().getSimpleName();
      plugin.getLogger().warning("Worker " + id + ": " + e);
      debug(
          "exception",
          Map.of(
              "error",
              e.toString(),
              "stack",
              Arrays.stream(e.getStackTrace()).limit(16).map(Object::toString).toList(),
              "mode",
              mode,
              "inventory",
              inventory(),
              "navigation",
              navigation.evidence(),
              "position",
              here()));
      recovery.failed(display);
      reset();
      nextWork = System.currentTimeMillis() + 5000;
      mode = "rest";
    }
  }

  /** Recomputed on this entity's region before accepting a courier's inventory transfer. */
  Map<String, Integer> currentRequests() {
    Map<String, Integer> result = new LinkedHashMap<>(needed(job));
    if (job != null) {
      // Requests describe the executable prerequisite and the finished block, not incompatible
      // alternative recipes inferred from the old fixed recipe catalog.
      if (job.kind == Job.Kind.PLACE && inventory().getOrDefault(job.material, 0) == 0)
        result.put(
            job.material, village.placementDemand(id, job.material, System.currentTimeMillis()));
      if ((job.kind == Job.Kind.MINE
              || result.containsKey("COAL")
              || result.containsKey("COBBLESTONE"))
          && ToolRecipes.tier(inventory()) == 0) {
        result.put("WOODEN_PICKAXE", 1);
        result.put("STONE_PICKAXE", 1);
      }
    }
    return result;
  }

  private boolean threatened() {
    if (!Bukkit.isOwnedByCurrentRegion(entity.getLocation(), 1)) return false;
    for (Entity other : entity.getNearbyEntities(8, 5, 8))
      if ((other instanceof Monster
              || other instanceof TNTPrimed
              || other instanceof org.bukkit.entity.minecart.ExplosiveMinecart)
          && Bukkit.isOwnedByCurrentRegion(other)) return true;
    return false;
  }

  private void sleep(long now, Pos at) {
    display = "sleeping / seeking bed";
    if (entity.isSleeping()) return;
    Pos bed = village.bed(id, at);
    if (bed == null) {
      entity.getPathfinder().stopPathfinding();
      display = "resting: no free bed";
      return;
    }
    if (at.distance2(bed) <= 9
        && owns(bed, 1)
        && location(bed).getBlock().getBlockData() instanceof Bed) {
      entity.sleep(location(bed));
      return;
    }
    walk(bed, now);
  }

  private Map<String, Integer> inventory() {
    return InventoryOps.summary(entity.getInventory());
  }

  private List<Job> offered(long now, Pos at) {
    Map<String, Map<String, Integer>> cache = new HashMap<>();
    List<Job> jobs =
        TaskSelection.offered(
            village,
            id,
            at,
            inventory(),
            now,
            j ->
                cache.computeIfAbsent(
                    j.kind + ":" + j.material + ":" + j.expected, k -> needed(j)));
    if (plugin.coreAi() == null) return jobs;
    policyChoice =
        plugin
            .coreAi()
            .rank(
                CoreAiCoordinator.Scope.JOBS,
                VillagerPolicies.jobs(
                    jobs,
                    at,
                    village.taskProject(id),
                    inventory().getOrDefault("BREAD", 0) < 3,
                    village.needs().danger(now),
                    j -> needed(j).values().stream().mapToInt(Integer::intValue).sum()),
                id);
    return VillagerPolicies.ordered(jobs, policyChoice, j -> j.id);
  }

  private CoreAiCoordinator.Choice policyChoice;
  private CoreAiCoordinator.Ticket policyTicket;

  private void choose(long now, Pos at) {
    if (now < nextWork) return;
    if (nextAdvice != null) {
      Decision advice = nextAdvice;
      nextAdvice = null;
      if (advice.action().equals("replan")) plugin.requestPlan(village, entity.getWorld());
      if (!advice.jobId().isEmpty()
          && offered(now, at).stream().anyMatch(j -> j.id.equals(advice.jobId()))) {
        apply(advice, now);
        return;
      }
    }
    if (awaiting) {
      if (now < responseDeadline) {
        fallback(now, at);
        return;
      }
      awaiting = false;
      generation++;
      recovery.failed("Local decision timed out; continuing with validated priorities");
    }
    boolean escalate =
        plugin.thinkingWhenStuck()
            && recovery.needsReasoning(now, village.jobs().stream().anyMatch(j -> !j.complete))
            && plugin.takeRecoveryTurn(village.id(), now);
    if (escalate || plugin.routineDecisions() && now >= nextThink) {
      nextThink = now + plugin.thinkMillis() + random.nextInt(5000);
      List<Job> jobs = offered(now, at);
      if (jobs.isEmpty() && !escalate) {
        debug(
            "decision_skipped",
            Map.of("reason", "No currently available jobs; waiting for planning/supply updates"));
        fallback(now, at);
        return;
      }
      Set<String> ids = new HashSet<>();
      jobs.forEach(j -> ids.add(j.id));
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("agent", id);
      report.put("role", village.role(id));
      report.put("position", at);
      report.put("inventory", inventory());
      report.put("stockpile", village.stock());
      report.put(
          "community_chests",
          village.chests().stream()
              .map(
                  p ->
                      Map.of(
                          "position",
                          p,
                          "items",
                          village.stock(p),
                          "age_seconds",
                          Math.min(9999, village.stockAge(p, now) / 1000)))
              .toList());
      report.put("stockpile_age_seconds", Math.min(9999, village.stockAge(now) / 1000));
      report.put("village_needs", village.needs().report(village, inventory(), now));
      report.put("jobs", jobs);
      if (escalate && jobs.isEmpty())
        report.put(
            "blocked_work", village.jobs().stream().filter(j -> !j.complete).limit(4).toList());
      report.put(
          "task_plans",
          jobs.stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      j -> j.project, j -> j, (a, b) -> a, LinkedHashMap::new))
              .values()
              .stream()
              .limit(3)
              .map(j -> TaskPlan.describe(j, inventory()))
              .toList());
      report.put("shared_facts", village.knowledge().report(now));
      report.put("supply_requests", village.supplyNeeds(now));
      report.put("material_deliveries", village.deliveries().report());
      Map<String, Object> ingredients = new LinkedHashMap<>();
      jobs.forEach(j -> ingredients.put(j.id, needed(j)));
      report.put("missing_ingredients_by_job", ingredients);
      report.put("nearby_requests", requests);
      report.put("last_failure_evidence", lastFailure);
      report.put(
          "crafting",
          "Server recipes execute one step at a time; LOG means any compatible natural log. Choose"
              + " work for crafting prerequisites.");
      Map<String, Integer> sites = new LinkedHashMap<>();
      for (String r : List.of("COBBLESTONE", "COAL", "LOG", "OAK_LOG", "WHEAT_SEEDS"))
        sites.put(r, plugin.resources(village.id(), r).size());
      report.put("resource_sites", sites);
      report.put("material_sources", MaterialSources.knowledge());
      report.put("current_project", village.taskProject(id));
      report.put("unfinished_steps", village.checkpoints(id));
      report.put("deposit_allowed", village.mayShareSurplus(id) && village.chest() != null);
      report.put("recent_results", village.memories(id));
      report.put("agent_experiences", mind.report());
      if (escalate) report.put("recovery_problem", recovery.problem(now));
      debug("decision_request", Map.of("reasoning", escalate, "observations", report));
      long token = ++generation;
      responseDeadline = now + plugin.decisionWaitMillis(escalate);
      boolean accepted =
          plugin
              .inference()
              .request(
                  escalate ? "recovery:" + village.id() : id,
                  new Gson().toJson(report),
                  ids,
                  escalate ? ReasoningMode.RECOVERY : ReasoningMode.NORMAL,
                  responseDeadline,
                  decision -> {
                    if (stopped) return;
                    entity
                        .getScheduler()
                        .run(
                            plugin,
                            t ->
                                plugin
                                    .connections()
                                    .read(
                                        () -> {
                                          if (stopped
                                              || generation != token
                                              || night
                                              || village.paused()) return;
                                          awaiting = false;
                                          if (job != null || mode.equals("deposit")) {
                                            nextAdvice = decision;
                                            if (decision != null)
                                              village.suggestion(
                                                  id, decision.action() + ": " + decision.reason());
                                            debug(
                                                "decision_deferred",
                                                Map.of(
                                                    "reason",
                                                    "Validated work already in progress; preserve"
                                                        + " current task"));
                                            return;
                                          }
                                          if (decision == null) {
                                            debug(
                                                "decision_failure",
                                                Map.of(
                                                    "result",
                                                    "No valid model response; local fallback"));
                                            village.suggestion(
                                                id,
                                                "No valid model response; using feasible local"
                                                    + " priorities");
                                            recovery.failed(
                                                "Local model did not return a valid decision");
                                            fallback(System.currentTimeMillis(), here());
                                            return;
                                          }
                                          apply(decision, System.currentTimeMillis());
                                        }),
                            () -> {});
                  });
      if (accepted) {
        awaiting = true;
        responseDeadline = now + plugin.decisionWaitMillis(escalate);
        if (escalate) {
          recovery.submitted(now);
          plugin
              .getLogger()
              .info("Recovery thinking for villager " + id + ": " + recovery.problem(now));
        }
        fallback(now, at);
        return;
      }
    }
    fallback(now, at);
  }

  private void begin(Job candidate) {
    var continuation = village.resume(id, Set.of(candidate.id), System.currentTimeMillis());
    if (continuation != null) supplyFor = continuation.parent();
    mind.begin(
        supplyFor == null ? candidate : supplyFor,
        here(),
        inventory(),
        System.currentTimeMillis(),
        () -> beginWork(candidate));
  }

  private void beginWork(Job candidate) {
    workPose.reset();
    waitingReason = "";
    job = candidate;
    village.checkpoint(id, job, supplyFor, "Working on validated step");
    policyTicket =
        plugin.coreAi() == null
            ? null
            : plugin.coreAi().begin(village.id(), id, policyChoice, candidate.id);
    village.taskProject(id, candidate.project);
    mode = "work";
    gathering.reset();
    village
        .knowledge()
        .progress(
            id,
            candidate.project,
            "Check prerequisites for " + candidate.kind,
            "",
            "",
            System.currentTimeMillis());
  }

  private void apply(Decision d, long now) {
    debug(
        "decision",
        Map.of(
            "action",
            d.action(),
            "job",
            d.jobId(),
            "material",
            d.material(),
            "reason",
            d.reason()));
    switch (d.action()) {
      case "work" -> {
        Job candidate =
            village.jobs().stream().filter(j -> j.id.equals(d.jobId())).findFirst().orElse(null);
        if (candidate == null || !village.claim(candidate.id, id, now)) {
          fallback(now, here());
          return;
        }
        begin(candidate);
      }
      case "deposit" -> {
        if (!village.mayShareSurplus(id) || village.chest() == null) {
          recovery.failed("Deposit rejected: finish committed projects before storing surplus");
          fallback(now, here());
          return;
        }
        mode = "deposit";
      }
      case "replan" -> {
        plugin.requestPlan(village, entity.getWorld());
        fallback(now, here());
      }
      case "gather" -> {
        // Choose a real task first: standalone stockpiling cannot bypass task accounting.
        Job candidate =
            offered(now, here()).stream()
                .filter(j -> d.jobId().isEmpty() || j.id.equals(d.jobId()))
                .filter(
                    j ->
                        needed(j).containsKey(d.material())
                            || d.material().equals("OAK_LOG") && needed(j).containsKey("LOG"))
                .findFirst()
                .orElse(null);
        if (candidate == null || !village.claim(candidate.id, id, now)) {
          recovery.failed("Gather rejected: ingredient not missing from an available current task");
          fallback(now, here());
          return;
        }
        begin(candidate);
        // Work executes the validated prerequisite, including crafting a tool before gathering.
        resource = d.material();
        mode = "work";
      }
      default -> {
        if (!offered(now, here()).isEmpty())
          recovery.failed("Rest rejected: available work; villagers have no energy meter");
        fallback(now, here());
      }
    }
    village.suggestion(id, d.action() + ": " + d.reason());
  }

  private void fallback(long now, Pos at) {
    List<Job> candidates = offered(now, at);
    var continuation =
        village.resume(
            id,
            candidates.stream().map(j -> j.id).collect(java.util.stream.Collectors.toSet()),
            now);
    if (continuation != null) {
      supplyFor = continuation.parent();
      begin(continuation.job());
      debug(
          "task_resumed",
          Map.of("job", continuation.job().id, "parent", supplyFor == null ? "" : supplyFor.id));
      return;
    }
    // Reuse carried building supplies before classifying them as surplus from the last project.
    for (Job candidate : candidates)
      if (needed(candidate).isEmpty() && village.claim(candidate.id, id, now)) {
        begin(candidate);
        return;
      }
    if (village.mayShareSurplus(id)) {
      if (RecipeCatalog.surplus(inventory(), true).isEmpty()) village.finishTasks(id);
      else if (village.chest() != null) {
        mode = "deposit";
        return;
      }
    }
    for (Job candidate : candidates)
      if (village.claim(candidate.id, id, now)) {
        begin(candidate);
        return;
      }
    mode = "rest";
    nextWork = now + 5000;
  }

  private void complete(long now) {
    workPose.reset();
    boolean committed = village.done(job.id, id);
    if (committed && job.project.startsWith("storage-") && job.material.equals("CHEST"))
      plugin.placeChest(village, entity.getWorld(), job.target);
    if (supplyFor == null) {
      if (committed)
        mind.succeeded(
            job.id,
            Map.of(
                "verified_job",
                job.id,
                "target",
                VillagerMind.position(job.target),
                "inventory_after",
                inventory(),
                "observed_block",
                location(job.target).getBlock().getType().name()));
      else mind.cancel("job_claim_no_longer_owned");
    }
    if (policyTicket != null && policyTicket.selected().equals(job.id) && committed) {
      plugin
          .coreAi()
          .outcome(
              policyTicket,
              true,
              Map.of(
                  "job",
                  job.id,
                  "project",
                  job.project,
                  "target",
                  job.target,
                  "executor_result",
                  "verified task step complete",
                  "inventory",
                  inventory()));
      policyTicket = null;
    }
    recovery.progress(now);
    nextWork = now + WorkerTuning.value(plugin, entity, "construction.interval_ms");
    if (supplyFor != null) {
      job = supplyFor;
      supplyFor = null;
      gathering.reset();
      mode = "work";
      navigation.stop();
    } else reset();
  }

  private boolean ingredients(long now, Pos at) {
    if (job.kind == Job.Kind.PLACE) {
      ToolActions.Preparation p = tools.prepareItem(job.material, now, at);
      if (p.ready()) return true;
      if (!p.gather().isEmpty()) {
        resource = p.gather();
        mode = "gather";
        gathering.reset();
      }
      return false;
    }
    Map<String, Integer> missing = needed(job);
    // Existing wheat can supply its own replanting seed at harvest time.
    if (job.kind == Job.Kind.FARM
        && owns(job.target, 1)
        && location(job.target).getBlock().getType() == Material.WHEAT) return true;
    if (missing.isEmpty()) return true;
    Pos chest = village.supplyChest(at, stock -> stock.getOrDefault("WHEAT_SEEDS", 0) > 0, now);
    if (chest == null) chest = village.supplyChest(at, stock -> true, now);
    if (chest != null
        && (village.stock(chest).getOrDefault("WHEAT_SEEDS", 0) > 0
            || village.stockAge(chest, now) > 30_000)) {
      if (at.distance2(chest) > 12) {
        walk(chest, now);
        return false;
      }
      if (!owns(chest, 1)) {
        walk(chest, now);
        return false;
      }
      if (location(chest).getBlock().getState() instanceof Chest c) {
        if (job.kind == Job.Kind.PLACE)
          InventoryOps.withdrawRecipe(
              c.getInventory(), entity.getInventory(), Material.valueOf(job.material));
        else
          InventoryOps.transfer(
              c.getInventory(), entity.getInventory(), Set.of(Material.WHEAT_SEEDS), 1);
        village.stock(chest, InventoryOps.summary(c.getInventory()), now);
      } else village.removeChest(chest);
      missing = needed(job);
      if (missing.isEmpty()) return true;
    }
    resource = missing.keySet().iterator().next();
    village.knowledge().progress(id, village.taskProject(id), "Gather " + resource, "", "", now);
    mode = "gather";
    gathering.reset();
    return false;
  }

  private void workWait(String reason) {
    display = "work: " + reason;
    if (!reason.equals(waitingReason)) {
      waitingReason = reason;
      debug(
          "work_wait",
          Map.of(
              "reason",
              reason,
              "target",
              job == null ? "none" : job.target,
              "inventory",
              inventory()));
    }
  }

  private void work(long now, Pos at) {
    if (job == null) {
      reset();
      return;
    }
    if (job.kind == Job.Kind.MINE
        && !prepareTools(Math.max(1, ToolRecipes.required(job.expected)), now, at)) return;
    if (!ingredients(now, at)) {
      workWait("preparing materials or fetching supplies");
      return;
    }
    if (at.distance2(job.target) > WorkerTuning.value(plugin, entity, "construction.reach_squared")
        || Math.abs(at.y() - job.target.y()) > 3) {
      workPose.reset();
      navigation.walkWork(job.stand, job.target, now);
      workWait("approaching target: " + navigation.status());
      return;
    }
    if (!owns(job.target, 1)) {
      workWait("waiting for target region ownership");
      return;
    }
    if (now < nextWork) {
      workWait("work interval");
      return;
    }
    Block block = location(job.target).getBlock();
    if ((job.kind == Job.Kind.MINE || job.kind == Job.Kind.CLEAR) && block.getType().isAir()) {
      complete(now);
      return;
    }
    if (job.kind == Job.Kind.PLACE
        && block.getType().name().equals(job.material)
        && (!(block.getBlockData() instanceof Bed bed)
            || block.getRelative(bed.getFacing()).getType() == block.getType())) {
      complete(now);
      return;
    }
    if (!workPose.accessible(block)) {
      workPose.reset();
      navigation.walkWork(job.stand, job.target, now);
      workWait("approaching a visible, reachable work target: " + navigation.status());
      return;
    }
    if (!workPose.ready(block, now)) {
      workWait("turning toward target before work");
      return;
    }
    waitingReason = "";
    if (!plugin.mayChange(entity, block, job.kind.name())) {
      fail(now, "Work blocked by protection");
      return;
    }
    if (plugin.experiments() != null
        && job.kind == Job.Kind.PLACE
        && !BlockRules.replaceable(block)
        && !BlockObservation.learnedClear(plugin.experiments().rules(), id, block)
        && !block.getType().isSolid()
        && !BlockObservation.dangerous(block.getType().name(), block.getBlockData().getAsString())
        && !block.isLiquid()
        && navigation.recoverSite(job.target, now)) {
      workWait("observing clutter or waiting for a classification trial");
      return;
    }
    if (job.kind == Job.Kind.CLEAR) {
      String problem = ClearingActions.work(plugin, entity, job, block);
      if (problem == null) complete(now);
      else fail(now, problem);
    } else if (job.kind == Job.Kind.PATH) {
      String problem = PathActions.work(plugin, entity, block);
      if (problem == null) complete(now);
      else fail(now, problem);
    } else if (job.kind == Job.Kind.FARM) {
      FarmingActions.Result result = FarmingActions.work(plugin, entity, block);
      if (result.complete()) {
        village.remember(id, result.problem(), true);
        complete(now);
      } else fail(now, result.problem());
    } else building.execute(job, block, now);
  }

  private void gather(long now, Pos at) {
    if (job == null) {
      reset();
      return;
    }
    if (job.kind == Job.Kind.PLACE && inventory().getOrDefault(job.material, 0) > 0) {
      mode = "work";
      gathering.reset();
      return;
    }
    if (!needed(job).containsKey(resource) && !Set.of("COAL", "COBBLESTONE").contains(resource)) {
      mode = "work";
      gathering.reset();
      return;
    }
    if (Set.of("COAL", "COBBLESTONE").contains(resource) && !prepareTools(1, now, at)) return;
    if (!needed(job).containsKey(resource)) {
      mode = "work";
      gathering.reset();
      return;
    }
    requests = needed(job);
    nearby.tick(requests, now);
    if (!gathering.gather(resource, now, at)) return;
    // Temporarily execute a supply step while retaining the parent task's lease and ingredients.
    Job mine =
        village.jobs().stream()
            .filter(j -> j.kind == Job.Kind.MINE && village.available(j.id, id, now))
            .findFirst()
            .orElse(null);
    if (Set.of("COBBLESTONE", "COAL").contains(resource)
        && mine != null
        && village.claim(mine.id, id, now)) {
      supplyFor = job;
      job = mine;
      village.checkpoint(id, job, supplyFor, "Obtaining supplies for parent task");
      mode = "work";
      return;
    }
    missingSupply(resource, now);
    plugin.requestPlan(village, entity.getWorld());
  }

  private boolean prepareTools(int tier, long now, Pos at) {
    ToolActions.Preparation preparation = tools.prepare(tier, now, at);
    if (preparation.ready()) return true;
    if (job != null && !preparation.gather().isEmpty()) {
      if (village.knowledge().blocked("resource:" + preparation.gather(), now)) {
        fail(now, "Tool prerequisite temporarily unavailable: " + preparation.gather());
      } else if (gathering.gather(preparation.gather(), now, at)) {
        missingSupply(preparation.gather(), now);
        plugin.requestPlan(village, entity.getWorld());
      }
    }
    return false;
  }

  private void missingSupply(String material, long now) {
    village.knowledge().need(material, village.taskProject(id), now);
    village
        .knowledge()
        .block(
            "resource:" + material,
            "No safe available source; resolve the supply prerequisite",
            now,
            60_000);
    fail(now, "No safe local source of " + material + "; requested a supply plan");
  }

  private void deposit(long now, Pos at) {
    if (!village.mayShareSurplus(id)) {
      reset();
      return;
    }
    Map<String, Integer> surplus = RecipeCatalog.surplus(inventory(), true);
    if (surplus.isEmpty()) {
      village.finishTasks(id);
      reset();
      return;
    }
    Pos chest = village.depositChest(at, now);
    if (chest == null) {
      fail(now, "No reachable community chest with available space; surplus retained");
      plugin.requestPlan(village, entity.getWorld());
      return;
    }
    if (at.distance2(chest) > 12) {
      walk(chest, now);
      return;
    }
    if (!owns(chest, 1)) {
      walk(chest, now);
      return;
    }
    if (location(chest).getBlock().getState() instanceof Chest c) {
      Map<String, Integer> beforeActor = inventory();
      Map<String, Integer> beforeChest = InventoryOps.summary(c.getInventory());
      int moved = 0;
      for (var item : surplus.entrySet())
        moved +=
            InventoryOps.transfer(
                entity.getInventory(),
                c.getInventory(),
                Set.of(Material.valueOf(item.getKey())),
                item.getValue());
      village.stock(chest, InventoryOps.summary(c.getInventory()), now);
      if (moved > 0) {
        TransferReceipts.record(
            plugin,
            village,
            id,
            "deposit",
            id,
            "chest:" + chest.key(),
            beforeActor,
            entity.getInventory(),
            beforeChest,
            c.getInventory());
        village.remember(
            id, "Completed all committed projects; stored " + moved + " surplus items", true);
        recovery.progress(now);
      }
      if (RecipeCatalog.surplus(inventory(), true).isEmpty()) {
        village.finishTasks(id);
        reset();
      } else {
        village.storageCapacity().request(RecipeCatalog.surplus(inventory(), true));
        village
            .storageCapacity()
            .observe(
                chest,
                c.getInventory().firstEmpty() >= 0,
                InventoryOps.partialStackTypes(c.getInventory()),
                now);
        plugin.requestPlan(village, entity.getWorld());
        village
            .knowledge()
            .block(
                "storage-full:" + chest.key(), "Full chest; try another shared store", now, 30_000);
        fail(now, "Community chest full; remaining surplus retained; checking other shared stores");
      }
    } else {
      village.removeChest(chest);
      fail(now, "Community chest missing; resources retained");
    }
  }

  private void walk(Pos destination, long now) {
    navigation.walk(destination, now);
  }

  private void fail(long now, String reason) {
    lastFailure = FailureEvidence.inspect(plugin, entity, village, job, reason, needed(job), now);
    var evidence = new java.util.LinkedHashMap<String, Object>(lastFailure);
    evidence.put("navigation", navigation.evidence());
    evidence.put("mode", mode);
    String failureId = java.util.UUID.randomUUID().toString();
    evidence.put("failure_id", failureId);
    lastFailure = Map.copyOf(evidence);
    debug("failure", lastFailure);
    mind.failed(reason, lastFailure);
    navigation.mapFailure(failureId, job == null ? here() : job.target, now);
    if (plugin.coreAi() != null) {
      if (policyTicket != null) plugin.coreAi().outcome(policyTicket, false, lastFailure);
      else plugin.coreAi().roadblock(village.id(), id, lastFailure);
      policyTicket = null;
    }
    recovery.failed(reason);
    village
        .knowledge()
        .progress(id, village.taskProject(id), "Reconsider blocked step", reason, "", now);
    if (job != null) village.failed(job.id, id, now, reason);
    else village.remember(id, reason, false);
    reset();
    mode = "rest";
    nextWork = now + 5000;
    nextThink = 0;
  }

  private void reset() {
    village.checkpoint(id, job, supplyFor, "Interrupted; retained for resumption");
    deliveries.cancel();
    mind.cancel("worker_reset");
    workPose.reset();
    waitingReason = "";
    policyTicket = null;
    policyChoice = null;
    job = null;
    supplyFor = null;
    requests = Map.of();
    awaiting = false;
    gathering.reset();
    mode = "idle";
    generation++;
    village.release(id);
    navigation.stop();
  }
}
