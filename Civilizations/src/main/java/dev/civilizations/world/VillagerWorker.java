package dev.civilizations.world;

import com.google.gson.Gson;
import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.ai.Decision;
import dev.civilizations.ai.DecisionHandoff;
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
  private final WorkerActivities activities = new WorkerActivities();
  private final PrerequisiteWait prerequisiteWait = new PrerequisiteWait();
  private WorkIssue.Action workAction = WorkIssue.Action.CONSTRUCTION;
  private final WorkerNavigation navigation;
  private final WorkMovementControl movementControl;
  private final WorkerEmergency emergency;
  private boolean escaping;
  private final BuildingActions building;
  private final WorkPose workPose;
  private final VillagerMind mind;
  private final WorkerProbe probe;
  private String waitingReason = "";
  private final GatheringActions gathering;
  private final RecoveryPolicy recovery;
  private final ToolActions tools;
  private final NearbyWork nearby;
  private final DeliveryActions deliveries;
  private final ProjectSupplies projectSupplies;
  private final YieldActions yielding;
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
    if (candidate != null
        && owns(candidate.target, 1)
        && WorkState.reusable(candidate, location(candidate.target).getBlock())) return Map.of();
    return tools.needed(candidate, inventory(), here(), System.currentTimeMillis());
  }

  private void debug(String type, Map<String, ?> data) {
    plugin.debug(village.id(), id, type, data);
  }

  private boolean awaiting;
  private DecisionHandoff nextAdvice;
  private long responseDeadline;
  private String mode = "idle", resource = "COBBLESTONE";
  private long nextThink, nextWork, nextStock;
  private long generation;
  private volatile boolean stopped;
  private boolean night, fleeing;
  private volatile String display = "starting";
  private final BlockLessons lessons;

  public VillagerWorker(CivilizationsPlugin plugin, Villager entity, Settlement village) {
    this.plugin = plugin;
    this.entity = entity;
    this.village = village;
    this.id = entity.getUniqueId().toString();
    lessons = new BlockLessons(plugin, entity, village);
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
    building = new BuildingActions(plugin, entity, this::fail, this::complete, this::placementWait);
    navigation =
        new WorkerNavigation(plugin, entity, village, this::navigationFailed, () -> workAction);
    emergency = new WorkerEmergency(plugin, entity, village);
    deliveries = new DeliveryActions(plugin, entity, village, navigation);
    projectSupplies = new ProjectSupplies(plugin, entity, village, navigation);
    yielding = new YieldActions(plugin, entity, village, navigation);
    recovery = new RecoveryPolicy(System.currentTimeMillis(), plugin.reasoningCooldown());
    gathering =
        new GatheringActions(plugin, entity, village, navigation, recovery, this::gatheringFailed);
    tools =
        new ToolActions(
            plugin,
            entity,
            village,
            navigation,
            (now, reason) -> {
              if (!navigation.recoveryPrerequisiteFailed(reason)) fail(now, reason);
            });
    // One prerequisite executor retains station choices and pending furnace batches across
    // normal work and temporary recovery. Recovery must not own a second smelting tracker.
    navigation.shareRecoveryTools(tools);
    probe =
        new WorkerProbe(
            plugin,
            entity,
            village,
            () -> supplyFor == null ? job : supplyFor,
            this::assignProbe,
            navigation::evidence,
            () -> status() + " | " + waitingReason);
  }

  public void probe(
      String action, String jobId, long duration, java.util.function.Consumer<List<String>> reply) {
    probe.request(action, jobId, duration, reply);
  }

  public java.util.concurrent.CompletableFuture<WorkerPosition> probePosition() {
    var result = new java.util.concurrent.CompletableFuture<WorkerPosition>();
    if (stopped) return java.util.concurrent.CompletableFuture.completedFuture(null);
    try {
      var task =
          entity
              .getScheduler()
              .run(
                  plugin,
                  ignored -> {
                    if (stopped || village.retired() || !entity.isValid()) {
                      result.complete(null);
                      return;
                    }
                    var at = entity.getLocation();
                    result.complete(
                        new WorkerPosition(
                            id, at.getWorld().getUID(), at.getX(), at.getY(), at.getZ()));
                  },
                  () -> result.complete(null));
      if (task == null) result.complete(null);
    } catch (RuntimeException error) {
      result.complete(null);
    }
    return result.completeOnTimeout(null, 4, java.util.concurrent.TimeUnit.SECONDS);
  }

  private boolean assignProbe(Job candidate, long now) {
    if (stopped) return false;
    if (job != null
        && (job.id.equals(candidate.id)
            || supplyFor != null && supplyFor.id.equals(candidate.id))) {
      excludeLearning("admin-commanded task probe");
      awaiting = false;
      nextAdvice = null;
      generation++;
      return true;
    }
    excludeLearning("admin-commanded task probe");
    reset(); // Checkpoints preserve interrupted work and carried resources.
    nextAdvice = null;
    if (!village.claim(candidate.id, id, now)) return false;
    nextWork = now;
    begin(candidate);
    return true;
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
                  probe.interrupt(TaskProbe.Result.INTERRUPTED, "Worker unloaded or retired");
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
    probe.interrupt(TaskProbe.Result.INTERRUPTED, "Worker/plugin stopped");
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
        "Activity owner: " + activities.owner(),
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
    if (!emergency.signal(cause)) navigation.damage(cause);
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
              if (!village.retired()) {
                try {
                  tickActive();
                } finally {
                  probe.sample();
                }
              }
            });
  }

  private void tickActive() {
    if (stopped || !entity.isValid() || entity.isDead()) return;
    try {
      long now = System.currentTimeMillis();
      Pos at = here();
      lessons.observe(now);
      if (awaiting && now >= responseDeadline) {
        awaiting = false;
        generation++;
        debug(
            "decision_expired",
            Map.of("result", "Continuing validated work; stale answer ignored"));
      }
      activities.tick(
          owner ->
              switch (owner) {
                case PAUSED -> activityPaused(now, at);
                case EMERGENCY -> activityEmergency(now, at);
                case THREAT -> activityThreat(now, at);
                case SLEEP -> activitySleep(now, at);
                case MAINTENANCE -> activityMaintenance(now, at);
                case RECOVERY -> activityRecovery(now, at);
                case YIELD -> activityYield(now, at);
                case PROJECT_SUPPLIES -> activityProjectSupplies(now, at);
                case DELIVERY -> activityDelivery(now, at);
                case COURIER_WAIT -> activityCourier(now, at);
                case PREREQUISITE_WAIT -> activityPrerequisiteWait(now, at);
                case WORK -> activityWork(now, at);
              });
    } catch (Exception e) {
      display = "recovering: " + e.getClass().getSimpleName();
      probe.failure(e.toString());
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

  private boolean activityPaused(long now, Pos at) {
    if (village.paused()) {
      excludeLearning("village paused");
      deliveries.cancel();
      navigation.stop("village_paused");
      movementControl.working(false);
      entity.getPathfinder().stopPathfinding();
      recovery.pause(now);
      display = "paused";
      return true;
    }
    return false;
  }

  private boolean activityEmergency(long now, Pos at) {
    if (emergency.needed()) {
      excludeLearning("physical emergency");
      if (!escaping) {
        navigation.stop("physical_emergency");
        deliveries.cancel();
        escaping = true;
      }
      movementControl.working(true);
      if (job != null) village.renew(job.id, id, now);
      if (supplyFor != null) village.renew(supplyFor.id, id, now);
      recovery.pause(now);
      if (emergency.tick(now, job == null ? null : job.stand)) {
        display = "escaping immediate physical danger; task retained";
        return true;
      }
      escaping = false;
      nextWork = now;
    }
    return false;
  }

  private boolean activityThreat(long now, Pos at) {
    if (threatened()) {
      movementControl.working(false);
      // Cancel our work once; repeatedly stopping the pathfinder would cancel vanilla fleeing.
      if (!fleeing) reset("threat_cancelled_work");
      fleeing = true;
      village.needs().threat(now);
      recovery.pause(now);
      display = "fleeing danger";
      return true;
    }
    fleeing = false;
    return false;
  }

  private boolean activitySleep(long now, Pos at) {
    long time = entity.getWorld().getTime();
    if (time >= 12500 && time < 23500) {
      movementControl.working(false);
      if (!night) {
        reset("night_sleep_interruption");
        night = true;
      }
      recovery.pause(now);
      sleep(now, at);
      return true;
    }
    if (night) {
      night = false;
      if (entity.isSleeping()) entity.wakeup();
      nextThink = 0;
    }
    return false;
  }

  private boolean activityMaintenance(long now, Pos at) {
    if (now >= nextStock) {
      nextStock = now + 5000;
      for (Pos chest : village.chests())
        if (at.distance2(chest) < 25
            && owns(chest, 1)
            && location(chest).getBlock().getState() instanceof Chest c)
          village.stock(chest, InventoryOps.summary(c.getInventory()), now);
    }
    if (job != null && !village.renew(job.id, id, now)
        || supplyFor != null && !village.renew(supplyFor.id, id, now)) reset("job_claim_lost");
    // Observe completed work before publishing supplies, gathering, or accepting a courier.
    if (completeIfSatisfied(now)) return true;
    return false;
  }

  private boolean activityRecovery(long now, Pos at) {
    // An active recovery owns movement through verification. Routine supply/yield selection
    // resumes afterward; sleep, danger, ownership loss and observed completion still run above.
    if (navigation.experimentActive()) {
      recovery.pause(now);
      movementControl.working(true);
      navigation.gates();
      if (navigation.experimentTick(now)) {
        display = navigation.status();
        return true;
      }
    }
    return false;
  }

  private boolean activityYield(long now, Pos at) {
    if (yielding.tick(now)) {
      movementControl.working(true);
      recovery.pause(now);
      excludeLearning("yielding to another worker");
      display = "moving aside for construction; own task retained";
      return true;
    }
    return false;
  }

  private boolean activityProjectSupplies(long now, Pos at) {
    requests = currentRequests();
    WorkIssue.Action previous = workAction;
    workAction = WorkIssue.Action.SUPPLY;
    if (job != null && projectSupplies.obtain(job, requests, at, now)) {
      movementControl.working(true);
      recovery.pause(now);
      excludeLearning("retrieving reserved project supplies");
      display = "retrieving reserved project supplies";
      return true;
    }
    workAction = previous;
    return false;
  }

  private boolean activityDelivery(long now, Pos at) {
    deliveries.publish(at, job, supplyFor, requests, now);
    nearby.tick(requests, now);
    if (!navigation.experimentActive() && deliveries.tick(now)) {
      excludeLearning("cooperative delivery");
      movementControl.working(true);
      recovery.pause(now);
      display = deliveries.status();
      return true;
    }
    return false;
  }

  private boolean activityCourier(long now, Pos at) {
    var incoming = village.deliveries().incoming(id, now);
    if (incoming != null && !navigation.experimentActive()) {
      excludeLearning("awaiting courier");
      navigation.stop();
      movementControl.working(true);
      recovery.pause(now);
      display = "awaiting courier: " + incoming.material();
      return true;
    }
    return false;
  }

  private boolean activityWork(long now, Pos at) {
    var progress = village.knowledge().worker(id);
    String step =
        mode + "|" + (job == null ? "" : job.id) + "|" + (progress == null ? "" : progress.step());
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
    if (now - navigation.progressAt() < 1000) recovery.pause(now);
    if (!awaiting && job != null && plugin.thinkingWhenStuck() && recovery.stalled(now)) {
      fail(now, recovery.problem(now));
      if (prerequisiteWait.issue() != null) return true;
      recovery.observedStall(now);
      nextWork = now;
      mode = "idle";
    }
    if (mode.equals("idle")) choose(now, at);
    movementControl.working(Set.of("work", "gather", "deposit", "supplies").contains(mode));
    display =
        (mode + (awaiting ? " (model considering next step)" : ""))
            + (job == null ? "" : " " + job.project)
            + (mode.equals("gather") ? " " + resource : "");
    switch (mode) {
      case "work" -> work(now, at);
      case "gather" -> gather(now, at);
      case "deposit" -> deposit(now, at);
      case "supplies" -> {
        if (!projectSupplies.surplus(at, now)) mode = "idle";
      }
      case "rest" -> {
        if (now >= nextWork) mode = "idle";
      }
      default -> {}
    }
    return true;
  }

  private String prerequisiteObservation() {
    return new TreeMap<>(inventory()) + "|" + new TreeMap<>(village.stock());
  }

  private boolean activityPrerequisiteWait(long now, Pos at) {
    if (prerequisiteWait.issue() == null) return false;
    if (prerequisiteWait.waiting(job == null ? "" : job.id, prerequisiteObservation(), now)) {
      recovery.pause(now);
      movementControl.working(false);
      display = "waiting for prerequisite; project retained: " + prerequisiteWait.issue().reason();
      return true;
    }
    if (job != null) {
      mode = "work";
      mind.begin(supplyFor == null ? job : supplyFor, at, inventory(), now, () -> {});
      debug("prerequisite_retry", Map.of("job", job.id, "project", job.project));
    }
    return false;
  }

  /** Recomputed on this entity's region before accepting a courier's inventory transfer. */
  Map<String, Integer> currentRequests() {
    Map<String, Integer> result = new LinkedHashMap<>(needed(job));
    if (job != null) {
      // Requests describe the executable prerequisite and the finished block, not incompatible
      // alternative recipes inferred from the old fixed recipe catalog.
      if (job.kind == Job.Kind.PLACE
          && inventory().getOrDefault(job.material, 0) == 0
          && !(owns(job.target, 1) && WorkState.reusable(job, location(job.target).getBlock())))
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
    String prerequisiteState = prerequisiteObservation();
    jobs = jobs.stream().filter(j -> prerequisiteWait.eligible(j.id, prerequisiteState)).toList();
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
    if (probe.resume(now)) return;
    // Local execution owns supported projects, including their gathering/crafting prerequisites.
    // Ask a teacher only when no admitted local step can be started.
    if (fallback(now, at)) return;
    if (nextAdvice != null) {
      Decision advice = nextAdvice.current(village.taskProject(id), now);
      nextAdvice = null;
      if (advice != null && advice.action().equals("replan"))
        plugin.requestPlan(village, entity.getWorld());
      if (advice != null
          && !advice.jobId().isEmpty()
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
      report.put("harvesting_capabilities", HarvestCatalog.report());
      report.put("current_project", village.taskProject(id));
      report.put("unfinished_steps", village.checkpoints(id));
      report.put("deposit_allowed", village.mayShareSurplus(id) && village.chest() != null);
      report.put("recent_results", village.memories(id));
      report.put("agent_experiences", mind.report());
      if (plugin.experiments() != null)
        report.put("diagnostic_memory", plugin.experiments().diagnosticReport(village.id()));
      if (escalate) report.put("recovery_problem", recovery.problem(now));
      debug("decision_request", Map.of("reasoning", escalate, "observations", report));
      long token = ++generation;
      responseDeadline = now + plugin.decisionWaitMillis(escalate);
      String requestedProject = village.taskProject(id);
      long adviceDeadline = responseDeadline;
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
                                          var handoff =
                                              new DecisionHandoff(
                                                  decision, requestedProject, adviceDeadline);
                                          if (decision != null
                                              && handoff.current(
                                                      village.taskProject(id),
                                                      System.currentTimeMillis())
                                                  == null) {
                                            debug(
                                                "decision_obsolete",
                                                Map.of(
                                                    "reason",
                                                    "Goal changed or observation expired while"
                                                        + " CoreAI continued working"));
                                            return;
                                          }
                                          if (job != null || mode.equals("deposit")) {
                                            nextAdvice = decision == null ? null : handoff;
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
                  },
                  failure -> {
                    if (plugin.experiments() != null)
                      plugin
                          .experiments()
                          .diagnose(
                              village.id(),
                              "decision:" + id,
                              failure.toString(),
                              Map.of("position", at, "reason", failure.toString()));
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
    prerequisiteWait.clear();
    workAction = WorkIssue.Action.CONSTRUCTION;
    workPose.reset();
    waitingReason = "";
    job = candidate;
    village.checkpoint(id, job, supplyFor, "Working on validated step");
    policyTicket =
        plugin.coreAi() == null || probe.active()
            ? null
            : plugin
                .coreAi()
                .begin(
                    village.id(),
                    id,
                    policyChoice,
                    candidate.id,
                    candidate.kind + ":" + candidate.material + ":" + candidate.expected);
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

  private boolean fallback(long now, Pos at) {
    if (projectSupplies.surplus(at, now)) {
      mode = "supplies";
      movementControl.working(true);
      recovery.pause(now);
      return true;
    }
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
      return true;
    }
    for (Job candidate :
        ProjectExecution.order(candidates, village.taskProject(id), j -> needed(j).isEmpty()))
      if (village.claim(candidate.id, id, now)) {
        String previousProject = village.taskProject(id);
        begin(candidate);
        debug(
            "local_project_step",
            Map.of(
                "job",
                candidate.id,
                "project",
                candidate.project,
                "continuing_project",
                candidate.project.equals(previousProject),
                "missing",
                needed(candidate),
                "executor",
                "local_controller",
                "completion_requires",
                "verified world/inventory outcome"));
        return true;
      }
    if (village.mayShareSurplus(id)) {
      if (RecipeCatalog.surplus(inventory(), true).isEmpty()) village.finishTasks(id);
      else if (village.chest() != null) {
        mode = "deposit";
        return true;
      }
    }
    mode = "rest";
    nextWork = now + 5000;
    return false;
  }

  private String workBlockBefore = "";

  private void complete(long now) {
    workPose.reset();
    String after = WorkState.snapshot(job, location(job.target).getBlock());
    boolean changed = !after.equals(workBlockBefore);
    String effect = changed ? "world_changed" : "already_satisfied";
    boolean committed = village.done(job.id, id, effect);
    if (committed) probe.completed(job.id, changed);
    if (committed)
      plugin.debug(
          village.id(),
          id,
          "job_progress",
          Map.of(
              "job",
              job.id,
              "project",
              job.project,
              "kind",
              job.kind,
              "target",
              job.target,
              "block_before",
              workBlockBefore,
              "block_after",
              after,
              "world_changed",
              changed,
              "project_complete",
              village.allComplete(job.project),
              "inventory_after",
              inventory()));
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
                location(job.target).getBlock().getType().name(),
                "effect",
                effect));
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
                  effect,
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
      if (owns(job.target, 1) && WorkState.reusable(job, location(job.target).getBlock()))
        return true;
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
    if (completeIfSatisfied(now)) return;
    workAction = WorkIssue.Action.CRAFT;
    if (job.kind == Job.Kind.MINE
        && !prepareTools(Math.max(1, ToolRecipes.required(job.expected)), now, at)) return;
    if (!ingredients(now, at)) {
      workWait("preparing materials or fetching supplies");
      return;
    }
    workAction = WorkIssue.Action.CONSTRUCTION;
    if (!WorkPose.withinConstructionReach(
        at, job.target, WorkerTuning.value(plugin, entity, "construction.reach_squared"))) {
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
    workBlockBefore = WorkState.snapshot(job, block);
    if (job.kind == Job.Kind.PLACE) {
      var obstruction = PlacementSpace.obstruction(block, PlacementSpace.data(job));
      if (obstruction != null) {
        placementWait(now, obstruction);
        return;
      }
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

  private boolean completeIfSatisfied(long now) {
    if (job == null || !owns(job.target, 1)) return false;
    Block block = location(job.target).getBlock();
    if (!WorkState.satisfied(job, block)) return false;
    workBlockBefore = WorkState.snapshot(job, block);
    complete(now);
    return true;
  }

  private void placementWait(long now, PlacementSpace.Obstruction obstruction) {
    workPose.reset();
    recovery.pause(now);
    var shapes = PlacementSpace.shapes(location(job.target).getBlock(), PlacementSpace.data(job));
    // Step out even when another occupant happened to be returned first by the world query.
    if (shapes.stream().anyMatch(box -> box.overlaps(entity.getBoundingBox()))) {
      Pos stand =
          WorkPositions.choose(
              entity,
              job.stand,
              job.target,
              WorkerTuning.value(plugin, entity, "construction.reach_squared"),
              Set.of(),
              p -> PlacementSpace.fits(entity, p, shapes));
      if (stand != null) navigation.walkExact(stand, 0, now);
      workWait(
          "stepping out of placement space at "
              + obstruction.block().key()
              + (stand == null
                  ? "; no clear supported stand observed yet"
                  : " toward " + stand.key()));
    } else {
      yielding.request(obstruction, job, now);
      excludeLearning("construction occupied by another entity");
      navigation.stop();
      entity.getPathfinder().stopPathfinding();
      workWait(
          "placement space at "
              + obstruction.block().key()
              + " occupied by "
              + obstruction.type()
              + " "
              + obstruction.entity()
              + "; task and materials retained");
    }
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
    if (!needed(job).containsKey(resource)) {
      mode = "work";
      gathering.reset();
      return;
    }
    if (HarvestCatalog.required(resource) > 0
        && !prepareTools(HarvestCatalog.required(resource), now, at)) return;
    if (!needed(job).containsKey(resource)) {
      mode = "work";
      gathering.reset();
      return;
    }
    requests = needed(job);
    nearby.tick(requests, now);
    workAction = WorkIssue.Action.GATHER;
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
    workAction = WorkIssue.Action.CRAFT;
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
    workAction = WorkIssue.Action.RESOURCE_SEARCH;
    village.knowledge().need(material, village.taskProject(id), now);
    // A completed local scan is not evidence that every worker/neighborhood lacks this resource.
    fail(
        now,
        "Local search found no available source of " + material + "; requested a supply plan",
        gathering.selectionUnavailable()
            ? PolicyEvidence.selectionFailure(
                PolicyEvidence.SelectionFailure.LOCAL_RESOURCE_SEARCH_EXHAUSTED,
                Map.of("resource", material, "search_position", here()))
            : Map.of());
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

  private void navigationFailed(long now, String reason) {
    if (!yielding.failed(now, reason) && !deliveries.failed(now, reason)) {
      WorkIssue.Action action = navigation.actionKind();
      // The callback belongs to the command that started navigation, even if worker mode changed.
      fail(
          now,
          actionIssue(reason, action == null ? workAction : action, navigation.actionTarget()),
          Map.of());
    }
  }

  private void gatheringFailed(long now, String reason) {
    workAction = WorkIssue.Action.GATHER;
    fail(now, reason);
  }

  private WorkIssue actionIssue(String reason) {
    return actionIssue(reason, workAction, null);
  }

  private WorkIssue actionIssue(String reason, WorkIssue.Action action, Pos commandedTarget) {
    String wanted =
        switch (action) {
          case GATHER -> gathering.resource();
          case CRAFT -> tools.requestedItem();
          default -> resource;
        };
    Pos target =
        switch (action) {
          case RESOURCE_SEARCH -> here();
          case GATHER -> gathering.target();
          case CONSTRUCTION -> job == null ? navigation.actionTarget() : job.target;
          default -> navigation.actionTarget();
        };
    if (target == null) target = here();
    if (commandedTarget != null) target = commandedTarget;
    WorkIssue.Retry retry =
        action == WorkIssue.Action.CONSTRUCTION
            ? WorkIssue.Retry.REPLAN_TASK
            : action == WorkIssue.Action.GATHER
                    && !HarvestCatalog.supported(wanted)
                    && !wanted.equals("WHITE_WOOL")
                ? WorkIssue.Retry.WAIT_FOR_CHANGE
                : WorkIssue.Retry.REOBSERVE_ACTION;
    return new WorkIssue(action, target, wanted, reason, retry);
  }

  private void fail(long now, String reason) {
    fail(now, reason, Map.of());
  }

  private void fail(long now, String reason, Map<String, ?> selectionEvidence) {
    fail(now, actionIssue(reason), selectionEvidence);
  }

  private void fail(long now, WorkIssue issue, Map<String, ?> selectionEvidence) {
    String reason = issue.reason();
    if (WorkFailure.classify(reason) == WorkFailure.INVENTORY_CAPACITY
        && projectSupplies.makeRoom(job, reason)) {
      recovery.pause(now);
      excludeLearning("inventory capacity interruption");
      workWait("reserved a project supply bundle; retrying the same task");
      return;
    }
    lastFailure =
        FailureEvidence.inspect(
            plugin, entity, village, job, reason, needed(job), now, issue.target());
    var evidence = new java.util.LinkedHashMap<String, Object>(lastFailure);
    evidence.put("navigation", navigation.evidence());
    evidence.put("mode", mode);
    evidence.put("failed_action", issue);
    evidence.put("activity_owner", activities.owner());
    evidence.put("root_job", supplyFor == null ? job == null ? "" : job.id : supplyFor.id);
    evidence.put("parent_penalized", job != null && !issue.prerequisite());
    evidence.putAll(selectionEvidence);
    String failureId = java.util.UUID.randomUUID().toString();
    evidence.put("failure_id", failureId);
    lastFailure = Map.copyOf(evidence);
    debug("failure", lastFailure);
    if (plugin.experiments() != null)
      plugin
          .experiments()
          .diagnose(
              village.id(),
              issue.action() + ":" + issue.target().key() + ":" + issue.resource(),
              reason,
              lastFailure);
    probe.failure(reason, lastFailure);
    navigation.mapFailure(failureId, issue.target(), now);
    if (issue.prerequisite() && job != null) {
      deferPrerequisite(issue, now);
      return;
    }
    mind.failed(reason, lastFailure);
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
    reset("failure_cancelled_work: " + reason);
    mode = "rest";
    nextWork = now + 5000;
    nextThink = 0;
  }

  private void deferPrerequisite(WorkIssue issue, long now) {
    excludeLearning("prerequisite failed; construction feasibility not established");
    mind.cancel("prerequisite_interruption: " + issue.reason());
    if (plugin.coreAi() != null) plugin.coreAi().roadblock(village.id(), id, lastFailure);
    village.checkpoint(
        id, job, supplyFor, "Retained while resolving " + issue.action() + ": " + issue.reason());
    village
        .knowledge()
        .progress(id, village.taskProject(id), "Waiting for prerequisite", issue.reason(), "", now);
    // No failed(job), release, or reset: retain this task and its materials through the wait.
    navigation.stop("prerequisite_wait");
    gathering.reset();
    workPose.reset();
    recovery.pause(now);
    awaiting = false;
    nextAdvice = null;
    generation++;
    if (issue.retry() == WorkIssue.Retry.WAIT_FOR_CHANGE) {
      prerequisiteWait.deferUntilChanged(job.id, prerequisiteObservation());
      debug("prerequisite_deferred", lastFailure);
      reset("unsupported_prerequisite_saved_for_changed_supplies");
      nextWork = now;
      return;
    }
    prerequisiteWait.defer(job.id, issue, prerequisiteObservation(), now + 5000);
    mode = "work";
    debug("prerequisite_deferred", lastFailure);
  }

  private void reset() {
    reset("worker_reset");
  }

  private void reset(String reason) {
    prerequisiteWait.clear();
    workAction = WorkIssue.Action.CONSTRUCTION;
    village.checkpoint(id, job, supplyFor, reason + "; retained for resumption");
    deliveries.cancel();
    yielding.cancel();
    mind.cancel(reason);
    workPose.reset();
    waitingReason = "";
    policyTicket = null;
    policyChoice = null;
    job = null;
    supplyFor = null;
    requests = Map.of();
    awaiting = false;
    nextAdvice = null;
    gathering.reset();
    mode = "idle";
    generation++;
    village.release(id);
    navigation.stop(reason);
  }

  private void excludeLearning(String reason) {
    if (policyTicket != null) {
      plugin
          .coreAi()
          .outcome(policyTicket, false, Map.of("interruption", reason, "attributable", false));
      policyTicket = null;
    }
  }
}
