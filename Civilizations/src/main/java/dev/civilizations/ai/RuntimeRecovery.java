package dev.civilizations.ai;

/** Lifecycle-thread policy: retry transient failures indefinitely without rapid restart loops. */
final class RuntimeRecovery {
  private int failures, unhealthy;
  private long healthySince = -1;

  long retrySeconds() {
    unhealthy = 0;
    healthySince = -1;
    failures = Math.min(failures + 1, 6);
    return Math.min(300, 15L << (failures - 1));
  }

  boolean restartForHealth(boolean healthy, long nowNanos) {
    if (!healthy) {
      healthySince = -1;
      return ++unhealthy >= 3;
    }
    unhealthy = 0;
    if (healthySince < 0) healthySince = nowNanos;
    if (nowNanos - healthySince >= java.util.concurrent.TimeUnit.SECONDS.toNanos(60)) failures = 0;
    return false;
  }
}
