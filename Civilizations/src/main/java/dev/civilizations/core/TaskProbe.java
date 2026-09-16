package dev.civilizations.core;

import java.util.*;

/** One commanded task attempt. Observations are evidence; only the executor can verify success. */
public final class TaskProbe {
  public enum Result {
    RUNNING,
    PASS,
    ALREADY_SATISFIED,
    TIMEOUT,
    CANCELLED,
    INTERRUPTED
  }

  private final String id = UUID.randomUUID().toString();
  private final Job task;
  private final long started, deadline;
  private final Pos origin;
  private final Map<String, Integer> inventoryBefore;
  private Result result = Result.RUNNING;
  private String reason = "", lastFailure = "", state = "assigned";
  private int failures;
  private double maximumDisplacement;
  private long ended;
  private Map<String, Object> observation = Map.of();

  public TaskProbe(Job task, long now, long duration, Pos origin, Map<String, Integer> inventory) {
    if (duration <= 0) throw new IllegalArgumentException("Probe duration must be positive");
    this.task = task.copy();
    this.started = now;
    this.deadline = Math.addExact(now, duration);
    this.origin = origin;
    this.inventoryBefore = Map.copyOf(inventory);
  }

  public Job task() {
    return task.copy();
  }

  public boolean active() {
    return result == Result.RUNNING;
  }

  public Result result() {
    return result;
  }

  public void observe(
      long now,
      Pos position,
      Map<String, Integer> inventory,
      String state,
      String targetBlock,
      Map<String, ?> navigation) {
    if (!active()) return;
    this.state = state;
    maximumDisplacement = Math.max(maximumDisplacement, Math.sqrt(origin.distance2(position)));
    var delta = new TreeMap<String, Integer>();
    inventoryBefore.forEach((key, value) -> delta.put(key, -value));
    inventory.forEach((key, value) -> delta.merge(key, value, Integer::sum));
    delta.values().removeIf(value -> value == 0);
    observation =
        Map.of(
            "position",
            position,
            "inventory",
            Map.copyOf(inventory),
            "inventory_delta",
            delta,
            "target_block",
            targetBlock,
            "navigation",
            Collections.unmodifiableMap(new LinkedHashMap<>(navigation)));
    if (now >= deadline)
      finish(
          Result.TIMEOUT,
          "No executor-verified completion within the observation window; last state: " + state,
          now);
  }

  public void failure(String message) {
    if (!active()) return;
    failures++;
    lastFailure = message;
  }

  public void verified(String job, boolean changed, long now) {
    if (!active() || !task.id.equals(job)) return;
    end(
        changed ? Result.PASS : Result.ALREADY_SATISFIED,
        changed
            ? "Executor committed a verified world change"
            : "Target was already satisfied; no action proven",
        now);
  }

  public void finish(Result result, String reason, long now) {
    if (!active()) return;
    if (result == Result.RUNNING || result == Result.PASS || result == Result.ALREADY_SATISFIED)
      throw new IllegalArgumentException("Use verified() for completion");
    end(result, reason, now);
  }

  private void end(Result result, String reason, long now) {
    this.result = result;
    this.reason = reason;
    ended = now;
  }

  public Map<String, Object> evidence(long now) {
    var result = new LinkedHashMap<String, Object>();
    result.put("probe", id);
    result.put("job", task.id);
    result.put("project", task.project);
    result.put("kind", task.kind);
    result.put("target", task.target);
    result.put("result", this.result.name());
    result.put("elapsed_ms", (active() ? now : ended) - started);
    result.put("reason", reason);
    result.put("state", state);
    result.put("failures", failures);
    result.put("last_failure", lastFailure);
    result.put("maximum_displacement_blocks", maximumDisplacement);
    result.put("inventory_before", inventoryBefore);
    result.put("observation", observation);
    return Collections.unmodifiableMap(result);
  }

  public String summary(long now) {
    return "Probe "
        + id.substring(0, 8)
        + " "
        + result
        + " | job="
        + task.id
        + " | "
        + ((active() ? now : ended) - started) / 1000
        + "s | moved="
        + String.format(Locale.ROOT, "%.1f", maximumDisplacement)
        + " blocks from start"
        + " | failures="
        + failures
        + " | "
        + (active() ? state : reason)
        + (lastFailure.isEmpty() ? "" : " | last failure=" + lastFailure);
  }
}
