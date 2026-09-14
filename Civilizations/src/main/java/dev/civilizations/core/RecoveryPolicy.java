package dev.civilizations.core;

/** Entity-owned failure history; escalation is bounded and does not authorize world changes. */
public final class RecoveryPolicy {
  private final long cooldownMillis;
  private int failures;
  private long lastProgress, nextReasoning;
  private String problem = "";

  public RecoveryPolicy(long now, long cooldownMillis) {
    lastProgress = now;
    this.cooldownMillis = cooldownMillis;
  }

  public void failed(String reason) {
    failures++;
    problem = reason;
  }

  public void progress(long now) {
    failures = 0;
    lastProgress = now;
    problem = "";
  }

  public void pause(long now) {
    lastProgress = now;
  }

  public boolean stalled(long now) {
    return now - lastProgress >= 90_000;
  }

  public void observedStall(long now) {
    failures = Math.max(2, failures);
    lastProgress = now;
  }

  public boolean needsReasoning(long now, boolean hasWork) {
    return now >= nextReasoning && (failures >= 2 || hasWork && now - lastProgress >= 90_000);
  }

  public void submitted(long now) {
    nextReasoning = now + cooldownMillis;
  }

  public String problem(long now) {
    return problem.isEmpty()
        ? "No verified task progress for " + Math.max(0, (now - lastProgress) / 1000) + " seconds"
        : problem;
  }
}
