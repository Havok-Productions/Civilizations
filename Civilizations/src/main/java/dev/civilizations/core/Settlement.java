package dev.civilizations.core;

import java.util.*;

/** All access through synchronized methods. No world objects or file I/O. */
public final class Settlement {
  public record Memory(long time, String result) {}

  public static final class Agent {
    public String id;
    public String role;
    public List<Memory> memories = new ArrayList<>();
    public long completed;
    public String taskProject = "";
    public Set<String> committedProjects = new HashSet<>();
    public String lastSuggestion = "";
    public List<TaskCheckpoint> taskCheckpoints = new ArrayList<>();

    public Agent() {}

    public Agent(String id, String role) {
      this.id = id;
      this.role = role;
    }
  }

  public static final class Data {
    public int schema = 1;
    public String id = UUID.randomUUID().toString();
    public String world;
    public Pos center;
    public int radius;
    public Pos chest;
    public List<Pos> chests = new ArrayList<>();
    public Set<String> absorbedIds = new HashSet<>();
    public List<Pos> areas = new ArrayList<>();
    public Map<String, Pos> memberPositions = new LinkedHashMap<>();
    public List<RepairBlock> repairBlocks = new ArrayList<>();
    public List<Pos> beds = new ArrayList<>();
    public List<Job> jobs = new ArrayList<>();
    public Map<String, Agent> agents = new LinkedHashMap<>();
    public Set<String> projects = new HashSet<>();
    public Set<String> playerBlocks = new HashSet<>();
    public boolean paused;
    public List<DesignRecord> designs = new ArrayList<>();
    public List<DesignProposal> proposals = new ArrayList<>();
    public Set<Pos> designReservations = new HashSet<>();
    public List<String> designFeedback = new ArrayList<>();
    public Pos craftingTable;
    public List<VillageKnowledge.Blockage> blockedFacts = new ArrayList<>();
    public List<VillageKnowledge.Supply> supplyNeeds = new ArrayList<>();
    public Map<String, VillageKnowledge.Progress> progress = new LinkedHashMap<>();
  }

  public synchronized List<RepairBlock> repairBlocks() {
    return List.copyOf(data.repairBlocks);
  }

  public synchronized boolean recordRepair(RepairBlock block) {
    if (data.repairBlocks.size() >= 2048
        || data.repairBlocks.stream().anyMatch(b -> b.position().equals(block.position())))
      return false;
    data.repairBlocks.add(block);
    return true;
  }

  private final Data data;
  private volatile boolean retired;

  public boolean retired() {
    return retired;
  }

  public void retire() {
    retired = true;
  }

  private record Stock(Map<String, Integer> items, long at) {}

  private final Map<Pos, Stock> stores = new LinkedHashMap<>();

  public synchronized List<Pos> chests() {
    return List.copyOf(data.chests);
  }

  public synchronized List<Pos> areas() {
    return List.copyOf(data.areas);
  }

  public synchronized Set<String> absorbedIds() {
    return Set.copyOf(data.absorbedIds);
  }

  public synchronized void position(String id, Pos p) {
    if (data.agents.containsKey(id)) {
      data.memberPositions.put(id, p);
      if (data.areas.size() < 16
          && data.areas.stream().noneMatch(a -> a.horizontal2(p) <= 24L * 24)) data.areas.add(p);
    }
  }

  public synchronized List<Pos> connectionPoints() {
    Set<Pos> anchors = new LinkedHashSet<>(data.memberPositions.values());
    anchors.add(data.center);
    anchors.addAll(data.beds);
    anchors.addAll(data.chests);
    return List.copyOf(anchors);
  }

  private final DeliveryBoard deliveries = new DeliveryBoard();

  public DeliveryBoard deliveries() {
    return deliveries;
  }

  private final StorageCapacity storageCapacity = new StorageCapacity();

  public StorageCapacity storageCapacity() {
    return storageCapacity;
  }

  public synchronized void removeChest(Pos p) {
    data.chests.remove(p);
    stores.remove(p);
    stock = Map.of();
    stockAt = 0;
    if (Objects.equals(data.chest, p))
      data.chest = data.chests.isEmpty() ? null : data.chests.getFirst();
  }

  public synchronized Map<String, Integer> stock(Pos p) {
    Stock s = stores.get(p);
    return s == null ? Map.of() : s.items();
  }

  public synchronized long stockAge(Pos p, long now) {
    Stock s = stores.get(p);
    return s == null ? Long.MAX_VALUE : Math.max(0, now - s.at());
  }

  public synchronized void stock(Pos p, Map<String, Integer> items, long now) {
    if (data.chests.contains(p)) stores.put(p, new Stock(Map.copyOf(items), now));
  }

  public synchronized Pos supplyChest(
      Pos near, java.util.function.Predicate<Map<String, Integer>> useful, long now) {
    return data.chests.stream()
        .filter(p -> !knowledge.blocked("route:" + p.key(), now))
        .filter(p -> useful.test(stock(p)))
        .min(Comparator.comparingLong(p -> p.distance2(near)))
        .orElse(null);
  }

  public synchronized Pos depositChest(Pos near, long now) {
    return data.chests.stream()
        .filter(p -> !knowledge.blocked("route:" + p.key(), now))
        .filter(p -> !knowledge.blocked("storage-full:" + p.key(), now))
        .min(Comparator.comparingLong(p -> p.distance2(near)))
        .orElse(null);
  }

  private java.util.function.BiConsumer<String, Map<String, ?>> observer = (w, e) -> {};

  public synchronized void observe(java.util.function.BiConsumer<String, Map<String, ?>> observer) {
    this.observer = observer;
  }

  private final Map<String, Long> gathering = new HashMap<>();
  private final Map<String, String> gatherOwners = new HashMap<>();
  private final Map<Pos, String> bedOwners = new HashMap<>();
  private Map<String, Integer> stock = Map.of();
  private long stockAt;
  private final VillageNeeds needs = new VillageNeeds();
  private final VillageKnowledge knowledge = new VillageKnowledge();

  public VillageKnowledge knowledge() {
    return knowledge;
  }

  public synchronized Pos craftingTable() {
    return data.craftingTable;
  }

  public synchronized void craftingTable(Pos p) {
    data.craftingTable = p;
  }

  public synchronized List<VillageKnowledge.Supply> supplyNeeds(long now) {
    Set<String> unfinished = new HashSet<>();
    data.jobs.stream().filter(j -> !j.complete).forEach(j -> unfinished.add(j.project));
    return knowledge.supplies(unfinished, now);
  }

  public synchronized void suggestion(String worker, String text) {
    Agent a = data.agents.get(worker);
    if (a != null) a.lastSuggestion = text.substring(0, Math.min(240, text.length()));
  }

  public synchronized String suggestion(String worker) {
    Agent a = data.agents.get(worker);
    return a == null || a.lastSuggestion == null ? "" : a.lastSuggestion;
  }

  public VillageNeeds needs() {
    return needs;
  }

  public synchronized String taskProject(String worker) {
    Agent a = data.agents.get(worker);
    return a == null || a.taskProject == null ? "" : a.taskProject;
  }

  public synchronized void taskProject(String worker, String project) {
    Agent a = data.agents.get(worker);
    if (a != null) {
      if (a.committedProjects == null) a.committedProjects = new HashSet<>();
      if (a.taskProject != null && !a.taskProject.isEmpty()) a.committedProjects.add(a.taskProject);
      a.taskProject = project;
      if (!project.isEmpty()) a.committedProjects.add(project);
    }
  }

  public synchronized boolean mayShareSurplus(String worker) {
    Agent a = data.agents.get(worker);
    if (a == null) return false;
    if (!taskProject(worker).isEmpty() && !allComplete(taskProject(worker))) return false;
    return a.committedProjects == null || a.committedProjects.stream().allMatch(this::allComplete);
  }

  /** Finished blocks for the current project, excluding another worker's live claims. */
  public synchronized int placementDemand(String worker, String material, long now) {
    String project = taskProject(worker);
    if (project.isEmpty()) return 1;
    int remaining = 0;
    for (Job j : data.jobs)
      if (!j.complete
          && j.kind == Job.Kind.PLACE
          && project.equals(j.project)
          && material.equals(j.material)
          && (j.owner == null || worker.equals(j.owner) || j.leaseUntil <= now)) remaining++;
    return Math.max(1, remaining);
  }

  public synchronized boolean taskComplete(String worker) {
    Agent a = data.agents.get(worker);
    if (a == null) return false;
    Set<String> projects = new HashSet<>();
    if (a.committedProjects != null) projects.addAll(a.committedProjects);
    if (!taskProject(worker).isEmpty()) projects.add(taskProject(worker));
    return !projects.isEmpty() && projects.stream().allMatch(this::allComplete);
  }

  public synchronized void finishTasks(String worker) {
    if (!taskComplete(worker)) return;
    Agent a = data.agents.get(worker);
    a.taskProject = "";
    a.committedProjects = new HashSet<>();
  }

  public Settlement(Data data) {
    this.data = data;
    if (data.repairBlocks == null) data.repairBlocks = new ArrayList<>();
    if (data.chests == null) data.chests = new ArrayList<>();
    if (data.chest != null && !data.chests.contains(data.chest)) data.chests.add(data.chest);
    if (data.areas == null) data.areas = new ArrayList<>();
    if (data.areas.isEmpty()) data.areas.add(data.center);
    if (data.absorbedIds == null) data.absorbedIds = new HashSet<>();
    if (data.memberPositions == null) data.memberPositions = new LinkedHashMap<>();
    if (data.designs == null) data.designs = new ArrayList<>();
    if (data.proposals == null) data.proposals = new ArrayList<>();
    data.agents
        .values()
        .forEach(
            a -> {
              if (a.taskCheckpoints == null) a.taskCheckpoints = new ArrayList<>();
            });
    if (data.designReservations == null) data.designReservations = new HashSet<>();
    if (data.designFeedback == null) data.designFeedback = new ArrayList<>();
    knowledge.restore(data.blockedFacts, data.supplyNeeds, data.progress);
  }

  public synchronized List<DesignRecord> designs() {
    return List.copyOf(data.designs);
  }

  public synchronized List<DesignProposal> proposals() {
    return List.copyOf(data.proposals);
  }

  public synchronized void proposal(DesignProposal proposal) {
    data.proposals.removeIf(p -> p.id().equals(proposal.id()));
    data.proposals.add(proposal);
  }

  public synchronized void acceptedProposal(String id) {
    data.proposals.removeIf(p -> p.id().equals(id));
  }

  public synchronized void checkpoint(String worker, Job job, Job parent, String reason) {
    Agent agent = data.agents.get(worker);
    if (agent == null || job == null) return;
    Job actual = find(job.id);
    if (actual == null || actual.complete) return;
    TaskContinuity.remember(
        agent.taskCheckpoints,
        new TaskCheckpoint(
            job.id, parent == null ? "" : parent.id, reason, System.currentTimeMillis()));
  }

  public synchronized List<TaskCheckpoint> checkpoints(String worker) {
    Agent agent = data.agents.get(worker);
    return agent == null ? List.of() : List.copyOf(agent.taskCheckpoints);
  }

  public record Continuation(Job job, Job parent) {}

  /** Claim a remembered child and its parent together; never steal another worker's lease. */
  public synchronized Continuation resume(String worker, Set<String> offered, long now) {
    for (TaskCheckpoint step : checkpoints(worker)) {
      Job child = find(step.job()), parent = step.parent().isEmpty() ? null : find(step.parent());
      if (child == null || !offered.contains(child.id) || !available(child.id, worker, now))
        continue;
      if (parent != null && (parent.complete || !available(parent.id, worker, now))) continue;
      if (parent != null) claim(parent.id, worker, now);
      claim(child.id, worker, now);
      return new Continuation(child.copy(), parent == null ? null : parent.copy());
    }
    return null;
  }

  public synchronized List<String> designFeedback() {
    return List.copyOf(data.designFeedback);
  }

  public synchronized void designFeedback(String message) {
    data.designFeedback.add(message.substring(0, Math.min(500, message.length())));
    while (data.designFeedback.size() > 8) data.designFeedback.removeFirst();
  }

  public synchronized Set<Pos> layoutOccupancy() {
    Set<Pos> result = new HashSet<>(data.designReservations);
    for (Job j : data.jobs) {
      result.add(j.target);
      result.add(j.stand);
      result.add(j.stand.add(0, -1, 0));
    }
    result.addAll(data.beds);
    data.repairBlocks.forEach(b -> result.add(b.position()));
    result.addAll(data.chests);
    if (data.craftingTable != null) result.add(data.craftingTable);
    return result;
  }

  public synchronized boolean addDesign(
      DesignRecord record, List<Job> jobs, Set<Pos> occupied, int activeLimit) {
    return addDesign(record, jobs, occupied, occupied, activeLimit);
  }

  public synchronized boolean addDesign(
      DesignRecord record,
      List<Job> jobs,
      Set<Pos> occupied,
      Set<Pos> construction,
      int activeLimit) {
    boolean defense =
        Set.of("wall", "mine").contains(record.kind())
            && data.designs.stream()
                .noneMatch(d -> d.kind().equals(record.kind()) && !allComplete(d.project()));
    if (data.paused
        || !defense
            && data.designs.stream().filter(d -> !allComplete(d.project())).count() >= activeLimit)
      return false;
    Set<Pos> old = layoutOccupancy();
    if (construction.stream().anyMatch(p -> old.contains(p) || playerProtected(p))) return false;
    if (!addProject(record.project(), jobs)) return false;
    data.designs.add(record);
    data.designReservations.addAll(occupied);
    return true;
  }

  public synchronized String id() {
    return data.id;
  }

  public synchronized String world() {
    return data.world;
  }

  public synchronized Pos center() {
    return data.center;
  }

  public synchronized int radius() {
    return data.radius;
  }

  public synchronized void radius(int r) {
    data.radius = r;
  }

  public synchronized Pos chest() {
    return data.chest;
  }

  public synchronized void chest(Pos p) {
    if (p == null) {
      removeChest(data.chest);
      return;
    }
    data.chest = p;
    if (!data.chests.contains(p)) data.chests.add(p);
  }

  public synchronized boolean paused() {
    return retired || data.paused;
  }

  public synchronized void paused(boolean value) {
    data.paused = value;
  }

  public synchronized int population() {
    return data.agents.size();
  }

  public synchronized Set<String> members() {
    return Set.copyOf(data.agents.keySet());
  }

  public synchronized boolean enroll(String uuid, int max) {
    if (data.agents.containsKey(uuid)) return true;
    if (data.agents.size() >= max) return false;
    int n = data.agents.size();
    data.agents.put(
        uuid, new Agent(uuid, n < 2 ? "gatherer" : n < 4 ? "builder" : "general worker"));
    return true;
  }

  public synchronized void remove(String id) {
    release(id);
    data.agents.remove(id);
    data.memberPositions.remove(id);
  }

  public synchronized String role(String id) {
    return data.agents.containsKey(id) ? data.agents.get(id).role : "worker";
  }

  public synchronized void remember(String id, String message, boolean completed) {
    Agent a = data.agents.get(id);
    if (a == null) return;
    a.memories.add(
        new Memory(
            System.currentTimeMillis(), message.substring(0, Math.min(message.length(), 220))));
    while (a.memories.size() > 40) a.memories.removeFirst();
    if (completed) a.completed++;
    observer.accept(
        id, Map.of("message", message, "verified_action", completed, "project", taskProject(id)));
  }

  public synchronized List<Memory> memories(String id) {
    Agent a = data.agents.get(id);
    if (a == null) return List.of();
    List<Memory> verified =
        a.memories.stream().filter(m -> !m.result().startsWith("Decision:")).toList();
    return List.copyOf(verified.subList(Math.max(0, verified.size() - 8), verified.size()));
  }

  public synchronized void stock(Map<String, Integer> items, long now) {
    stock = Map.copyOf(items);
    stockAt = now;
    if (data.chest != null) stock(data.chest, items, now);
  }

  public synchronized Map<String, Integer> stock() {
    if (data.chests.isEmpty()) return stock;
    Map<String, Integer> result = new LinkedHashMap<>();
    for (Pos p : data.chests) stock(p).forEach((m, n) -> result.merge(m, n, Integer::sum));
    return Map.copyOf(result);
  }

  public synchronized long stockAge(long now) {
    if (data.chests.isEmpty()) return stockAt == 0 ? Long.MAX_VALUE : Math.max(0, now - stockAt);
    return data.chests.stream().mapToLong(p -> stockAge(p, now)).max().orElse(Long.MAX_VALUE);
  }

  public synchronized void beds(List<Pos> beds) {
    data.beds = new ArrayList<>(beds);
    bedOwners.keySet().retainAll(data.beds);
  }

  public synchronized List<Pos> beds() {
    return List.copyOf(data.beds);
  }

  public synchronized Pos bed(String id, Pos near) {
    for (var e : bedOwners.entrySet()) if (e.getValue().equals(id)) return e.getKey();
    Pos best =
        data.beds.stream()
            .filter(p -> !bedOwners.containsKey(p))
            .min(Comparator.comparingLong(p -> p.distance2(near)))
            .orElse(null);
    if (best != null) bedOwners.put(best, id);
    return best;
  }

  public synchronized boolean addProject(String name, List<Job> jobs) {
    if (retired || jobs.isEmpty() || data.projects.contains(name)) return false;
    Set<Pos> reserved = new HashSet<>();
    data.jobs.forEach(j -> reserved.add(j.target));
    reserved.addAll(data.chests);
    if (jobs.stream().anyMatch(j -> reserved.contains(j.target))) return false;
    data.projects.add(name);
    data.jobs.addAll(jobs);
    return true;
  }

  public synchronized boolean hasProject(String name) {
    return data.projects.contains(name);
  }

  public synchronized List<Job> jobs() {
    return data.jobs.stream().map(Job::copy).toList();
  }

  public synchronized boolean allComplete(String project) {
    return data.projects.contains(project)
        && data.jobs.stream().filter(j -> j.project.equals(project)).allMatch(j -> j.complete);
  }

  public synchronized boolean claim(String id, String worker, long now) {
    if (!available(id, worker, now)) return false;
    Job j = find(id);
    j.owner = worker;
    j.leaseUntil = now + 60_000;
    return true;
  }

  public synchronized boolean available(String id, String worker, long now) {
    Job j = find(id);
    if (retired || j == null || j.complete || j.retryAfter > now) return false;
    for (Job prior : data.jobs) {
      if (prior.id.equals(j.id)) break;
      if (prior.target.equals(j.target) && !prior.project.equals(j.project)) return false;
    }
    if (j.project.startsWith("design-")
        && data.jobs.stream()
            .anyMatch(
                prior ->
                    prior.project.equals(j.project) && !prior.complete && prior.phase < j.phase))
      return false;
    if (j.owner != null && !worker.equals(j.owner) && j.leaseUntil > now) return false;
    if (j.project.startsWith("wall-")
        && data.jobs.stream()
            .anyMatch(
                prior ->
                    prior.project.equals(j.project)
                        && !prior.complete
                        && prior.target.x() == j.target.x()
                        && prior.target.z() == j.target.z()
                        && prior.target.y() < j.target.y())) return false;
    if (j.project.split("@", 2)[0].equals("house")) {
      String origin = j.project.contains("@") ? j.project.substring(j.project.indexOf('@')) : "";
      if (data.projects.stream()
          .filter(
              p ->
                  p.startsWith("wall-")
                      && (p.contains("@") ? p.substring(p.indexOf('@')) : "").equals(origin))
          .anyMatch(p -> !allComplete(p))) return false;
      if (data.jobs.stream()
          .anyMatch(
              prior ->
                  prior.project.equals(j.project)
                      && !prior.complete
                      && prior.target.y() < j.target.y())) return false;
      if (j.material.equals("WHITE_BED")
          && data.jobs.stream()
              .anyMatch(
                  prior ->
                      prior.project.equals(j.project)
                          && !prior.complete
                          && !prior.material.equals("WHITE_BED"))) return false;
    }
    // Mine steps are ordered: no tunneling through a still-solid staircase.
    if (j.kind == Job.Kind.MINE)
      for (Job prior : data.jobs) {
        if (prior.id.equals(j.id)) break;
        if (prior.project.equals(j.project) && !prior.complete) return false;
      }
    return true;
  }

  public synchronized boolean renew(String id, String worker, long now) {
    Job j = find(id);
    if (j == null || j.complete || !worker.equals(j.owner) || j.leaseUntil < now) return false;
    j.leaseUntil = now + 60_000;
    return true;
  }

  public synchronized boolean done(String id, String worker) {
    Job j = find(id);
    if (j == null || !worker.equals(j.owner)) return false;
    j.complete = true;
    data.agents
        .values()
        .forEach(a -> TaskContinuity.completed(a.taskCheckpoints, id, System.currentTimeMillis()));
    j.everBuilt = j.kind != Job.Kind.CLEAR;
    j.owner = null;
    remember(worker, "Completed " + j.project + " at " + j.target.key(), true);
    knowledge.progress(
        worker,
        j.project,
        allComplete(j.project) ? "Project complete" : "Next validated project step",
        "",
        "Completed " + j.kind + " at " + j.target.key(),
        System.currentTimeMillis());
    return true;
  }

  public synchronized void damaged(String id) {
    Job j = find(id);
    if (j != null && j.everBuilt) j.complete = false;
  }

  public synchronized void failed(String id, String worker, long now, String reason) {
    Job j = find(id);
    if (j != null && worker.equals(j.owner)) {
      j.owner = null;
      j.failures++;
      j.blockedReason = reason;
      j.retryAfter = now + Math.min(300_000, 15_000L * j.failures);
    }
    remember(worker, reason, false);
  }

  public synchronized void release(String worker) {
    for (Job j : data.jobs) if (worker.equals(j.owner)) j.owner = null;
    bedOwners.values().removeIf(worker::equals);
    gatherOwners
        .entrySet()
        .removeIf(
            e -> {
              if (e.getValue().equals(worker)) {
                gathering.remove(e.getKey());
                return true;
              }
              return false;
            });
  }

  public synchronized boolean reserveGather(Pos p, String worker, long now) {
    String k = p.key();
    if (gathering.getOrDefault(k, 0L) > now && !worker.equals(gatherOwners.get(k))) return false;
    gathering.put(k, now + 30_000);
    gatherOwners.put(k, worker);
    return true;
  }

  public synchronized boolean protectedPos(Pos p) {
    return data.repairBlocks.stream().anyMatch(b -> b.position().equals(p))
        || data.designReservations.contains(p)
        || data.jobs.stream().anyMatch(j -> j.target.equals(p))
        || data.chests.contains(p)
        || p.equals(data.craftingTable);
  }

  public synchronized boolean ownsBuildSite(Pos p, String worker, long now) {
    return data.jobs.stream()
        .anyMatch(
            j ->
                j.kind == Job.Kind.PLACE
                    && !j.complete
                    && j.target.equals(p)
                    && worker.equals(j.owner)
                    && j.leaseUntil >= now);
  }

  public synchronized boolean gatherProtected(Pos p) {
    return protectedPos(p)
        || data.jobs.stream().anyMatch(j -> j.stand.equals(p) || j.stand.add(0, -1, 0).equals(p));
  }

  public synchronized boolean playerProtected(Pos p) {
    return data.playerBlocks.contains(p.key());
  }

  public synchronized void playerPlaced(Pos p) {
    data.playerBlocks.add(p.key());
  }

  private Job find(String id) {
    for (Job j : data.jobs) if (j.id.equals(id)) return j;
    return null;
  }

  public synchronized Data snapshot() {
    Data d = new Data();
    d.id = data.id;
    d.world = data.world;
    d.center = data.center;
    d.radius = data.radius;
    d.chest = data.chest;
    d.chests = new ArrayList<>(data.chests);
    d.areas = new ArrayList<>(data.areas);
    d.absorbedIds = new HashSet<>(data.absorbedIds);
    d.memberPositions = new LinkedHashMap<>(data.memberPositions);
    d.paused = data.paused;
    d.beds = new ArrayList<>(data.beds);
    d.repairBlocks = new ArrayList<>(data.repairBlocks);
    d.projects = new HashSet<>(data.projects);
    d.jobs = new ArrayList<>(jobs());
    d.playerBlocks = new HashSet<>(data.playerBlocks);
    d.designs = new ArrayList<>(data.designs);
    d.proposals = new ArrayList<>(data.proposals);
    d.designReservations = new HashSet<>(data.designReservations);
    d.designFeedback = new ArrayList<>(data.designFeedback);
    d.craftingTable = data.craftingTable;
    d.blockedFacts = knowledge.blocks();
    d.supplyNeeds = knowledge.supplySnapshot();
    d.progress = knowledge.progressSnapshot();
    data.agents.forEach(
        (id, a) -> {
          Agent b = new Agent(id, a.role);
          b.memories = new ArrayList<>(a.memories);
          b.completed = a.completed;
          b.taskProject = a.taskProject;
          b.lastSuggestion = a.lastSuggestion;
          b.taskCheckpoints = new ArrayList<>(a.taskCheckpoints);
          b.committedProjects =
              a.committedProjects == null ? new HashSet<>() : new HashSet<>(a.committedProjects);
          d.agents.put(id, b);
        });
    return d;
  }
}
