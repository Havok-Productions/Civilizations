package dev.civilizations.core;

/** A blocked action pauses its parent; it does not mark the parent construction step failed. */
public final class PrerequisiteWait {
  private final java.util.Map<String, String> deferred = new java.util.HashMap<>();

  public void deferUntilChanged(String job, String observation) {
    deferred.put(job, observation);
  }

  /** Only this worker's unchanged blocked step is deferred; other work remains eligible. */
  public boolean eligible(String job, String observation) {
    String previous = deferred.get(job);
    if (previous == null) return true;
    if (previous.equals(observation)) return false;
    deferred.remove(job);
    return true;
  }

  private String job = "", observation = "";
  private WorkIssue issue;
  private long retryAt;

  public void defer(String job, WorkIssue issue, String observation, long retryAt) {
    if (!issue.prerequisite()) throw new IllegalArgumentException("Not a prerequisite failure");
    this.job = job;
    this.issue = issue;
    this.observation = observation;
    this.retryAt = retryAt;
  }

  public WorkIssue issue() {
    return issue;
  }

  public boolean waiting(String currentJob, String observed, long now) {
    if (issue == null) return false;
    if (!job.equals(currentJob)
        || !observation.equals(observed)
        || issue.retry() != WorkIssue.Retry.WAIT_FOR_CHANGE && now >= retryAt) {
      clear();
      return false;
    }
    return true;
  }

  /** Clear the active pause; saved deferred jobs remain excluded until supplies change. */
  public void clear() {
    job = "";
    observation = "";
    issue = null;
    retryAt = 0;
  }
}
