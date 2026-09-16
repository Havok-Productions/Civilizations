package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import java.util.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.entity.Villager;

/** Region-owned diagnostic assignment and observation; it never changes blocks or items. */
final class WorkerProbe {
  private final CivilizationsPlugin plugin;
  private final Villager entity;
  private final Settlement village;
  private final Supplier<Job> current;
  private final BiPredicate<Job, Long> assign;
  private final Supplier<Map<String, Object>> navigation;
  private final Supplier<String> status;
  private TaskProbe attempt;
  private long nextReport;
  private boolean reported;

  WorkerProbe(
      CivilizationsPlugin plugin,
      Villager entity,
      Settlement village,
      Supplier<Job> current,
      BiPredicate<Job, Long> assign,
      Supplier<Map<String, Object>> navigation,
      Supplier<String> status) {
    this.plugin = plugin;
    this.entity = entity;
    this.village = village;
    this.current = current;
    this.assign = assign;
    this.navigation = navigation;
    this.status = status;
  }

  synchronized boolean active() {
    return attempt != null && attempt.active();
  }

  void request(String action, String jobId, long duration, Consumer<List<String>> reply) {
    var scheduled =
        entity
            .getScheduler()
            .run(
                plugin,
                t ->
                    plugin
                        .connections()
                        .read(
                            () -> {
                              var lines = command(action, jobId, duration);
                              plugin.probeEvent(
                                  village.id(),
                                  entity.getUniqueId().toString(),
                                  "probe_command",
                                  Map.of(
                                      "action",
                                      action,
                                      "requested_task",
                                      jobId,
                                      "reply",
                                      lines,
                                      "attempt_active",
                                      active()));
                              reply.accept(lines);
                            }),
                () -> reply.accept(List.of("Worker unloaded before the probe command could run.")));
    if (scheduled == null) reply.accept(List.of("Worker is no longer loaded."));
  }

  private synchronized List<String> command(String action, String requested, long duration) {
    long now = System.currentTimeMillis();
    String worker = entity.getUniqueId().toString();
    if (action.equals("jobs")) {
      var lines = new ArrayList<String>();
      lines.add(
          "Worker "
              + worker
              + "; nearest unfinished tasks (availability includes claims/order/cooldown):");
      village.jobs().stream()
          .filter(j -> !j.complete)
          .sorted(Comparator.comparingDouble(j -> here().distance2(j.target)))
          .limit(12)
          .forEach(
              j ->
                  lines.add(
                      j.id
                          + " | "
                          + j.kind
                          + " "
                          + j.material
                          + " | "
                          + j.project
                          + " at "
                          + j.target.key()
                          + " | available="
                          + village.available(j.id, worker, now)
                          + " | "
                          + village.unavailableReason(j.id, worker, now)));
      return lines;
    }
    if (action.equals("cancel")) {
      interrupt(
          TaskProbe.Result.CANCELLED, "Admin ended probe; task remains available for normal work");
      return List.of(attempt == null ? "No probe recorded." : attempt.summary(now));
    }
    if (action.equals("status")) {
      sample();
      return List.of(
          attempt == null ? "No probe recorded for this worker." : attempt.summary(now),
          "Evidence: plugins/Civilizations/debug/probes/events.jsonl");
    }
    if (active()) return List.of("A probe is already running. " + attempt.summary(now));
    if (village.retired() || !entity.isValid())
      return List.of("Worker/village is no longer active.");
    Job selected;
    if (requested.equalsIgnoreCase("auto")) {
      selected = current.get();
      if (selected != null && !village.available(selected.id, worker, now)) selected = null;
      if (selected == null)
        selected =
            village.jobs().stream()
                .filter(j -> village.available(j.id, worker, now))
                .min(Comparator.comparingDouble(j -> here().distance2(j.target)))
                .orElse(null);
    } else {
      var matches = village.jobs().stream().filter(j -> j.id.startsWith(requested)).toList();
      if (matches.size() != 1)
        return List.of("Job ID must identify exactly one task; use probe jobs.");
      selected = matches.getFirst();
    }
    if (selected == null) {
      Map<String, Long> reasons =
          village.jobs().stream()
              .filter(j -> !j.complete)
              .collect(
                  java.util.stream.Collectors.groupingBy(
                      j -> village.unavailableReason(j.id, worker, now),
                      TreeMap::new,
                      java.util.stream.Collectors.counting()));
      return List.of(
          "No available task for this worker. No execution trial started.",
          "Queued task blockers: " + reasons,
          "Retained designs: "
              + village.proposals().stream().map(p -> p.kind() + " | " + p.reason()).toList(),
          "Use /civ debug probe jobs to inspect individual tasks.");
    }
    if (!village.available(selected.id, worker, now))
      return List.of(
          "No execution trial started: " + village.unavailableReason(selected.id, worker, now));
    attempt =
        new TaskProbe(selected, now, duration, here(), InventoryOps.summary(entity.getInventory()));
    reported = false;
    nextReport = now;
    sample();
    plugin.probeEvent(village.id(), worker, "probe_started", attempt.evidence(now));
    if (!assign.test(selected, now))
      interrupt(TaskProbe.Result.INTERRUPTED, "Task claim changed before assignment");
    return List.of(
        attempt.summary(now),
        "Task assigned through the normal worker. Status: /civ debug probe status " + worker,
        "Evidence: plugins/Civilizations/debug/probes/events.jsonl");
  }

  /** Called instead of selecting a different job while a commanded attempt is running. */
  synchronized boolean resume(long now) {
    if (!active()) return false;
    Job target =
        village.jobs().stream()
            .filter(j -> j.id.equals(attempt.task().id))
            .findFirst()
            .orElse(null);
    if (target == null || target.complete) {
      interrupt(
          TaskProbe.Result.INTERRUPTED,
          "Task disappeared or was finished by another worker; this probe did not verify it");
      return false;
    }
    if (village.available(target.id, entity.getUniqueId().toString(), now))
      assign.test(target, now);
    return true;
  }

  synchronized void failure(String reason) {
    failure(reason, Map.of());
  }

  synchronized void failure(String reason, Map<String, ?> details) {
    if (!active()) return;
    attempt.failure(reason);
    var evidence = new LinkedHashMap<>(attempt.evidence(System.currentTimeMillis()));
    evidence.put("failure_evidence", Collections.unmodifiableMap(new LinkedHashMap<>(details)));
    plugin.probeEvent(village.id(), entity.getUniqueId().toString(), "probe_failure", evidence);
  }

  synchronized void completed(String jobId, boolean changed) {
    if (!active()) return;
    sample();
    attempt.verified(jobId, changed, System.currentTimeMillis());
    reportResult();
  }

  synchronized void sample() {
    if (!active()) return;
    try {
      observe();
    } catch (RuntimeException error) {
      // Diagnostics must not stop the worker's scheduled work loop.
      interrupt(TaskProbe.Result.INTERRUPTED, "Probe observation failed: " + error);
    }
  }

  private void observe() {
    long now = System.currentTimeMillis();
    Job task = attempt.task();
    var at = new Location(entity.getWorld(), task.target.x(), task.target.y(), task.target.z());
    String block =
        Bukkit.isOwnedByCurrentRegion(at, 1)
            ? WorkState.snapshot(task, at.getBlock())
            : "UNOBSERVED_REGION";
    String state = status.get();
    if (current.get() == null) {
      Job live = village.jobs().stream().filter(j -> j.id.equals(task.id)).findFirst().orElse(null);
      if (live != null && !village.available(live.id, entity.getUniqueId().toString(), now))
        state += " | task unavailable; retryAfter=" + live.retryAfter + " | " + live.blockedReason;
    }
    attempt.observe(
        now, here(), InventoryOps.summary(entity.getInventory()), state, block, navigation.get());
    if (!attempt.active()) reportResult();
    else if (now >= nextReport) {
      nextReport = now + 5000;
      plugin.probeEvent(
          village.id(),
          entity.getUniqueId().toString(),
          "probe_observation",
          attempt.evidence(now));
    }
  }

  synchronized void interrupt(TaskProbe.Result result, String reason) {
    if (!active()) return;
    attempt.finish(result, reason, System.currentTimeMillis());
    reportResult();
  }

  private void reportResult() {
    if (attempt == null || attempt.active() || reported) return;
    reported = true;
    long now = System.currentTimeMillis();
    plugin.probeEvent(
        village.id(), entity.getUniqueId().toString(), "probe_result", attempt.evidence(now));
    plugin.getLogger().info(attempt.summary(now));
  }

  private Pos here() {
    var at = entity.getLocation();
    return new Pos(at.getBlockX(), at.getBlockY(), at.getBlockZ());
  }
}
