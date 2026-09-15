package dev.civilizations.core;

import java.util.*;

/** Persistent unfinished steps; changing the current activity never erases a deferred goal. */
public final class TaskContinuity {
  private TaskContinuity() {}

  public static void remember(List<TaskCheckpoint> saved, TaskCheckpoint step) {
    saved.removeIf(old -> old.root().equals(step.root()) || old.root().equals(step.job()));
    saved.addFirst(step);
  }

  public static void completed(List<TaskCheckpoint> saved, String job, long now) {
    List<TaskCheckpoint> next = new ArrayList<>();
    for (TaskCheckpoint step : saved) {
      if (step.root().equals(job)) continue;
      next.add(
          step.job().equals(job)
              ? new TaskCheckpoint(step.parent(), "", "Supply step completed; resume parent", now)
              : step);
    }
    saved.clear();
    saved.addAll(next);
  }
}
