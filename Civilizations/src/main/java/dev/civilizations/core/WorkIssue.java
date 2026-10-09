package dev.civilizations.core;

import java.util.Objects;

/** Failure of an executable action; the retained construction goal is reported separately. */
public record WorkIssue(Action action, Pos target, String resource, String reason, Retry retry) {
  public enum Action {
    CONSTRUCTION,
    GATHER,
    CRAFT,
    SUPPLY,
    RESOURCE_SEARCH
  }

  public enum Retry {
    REPLAN_TASK,
    REOBSERVE_ACTION,
    WAIT_FOR_CHANGE
  }

  public WorkIssue {
    Objects.requireNonNull(action);
    Objects.requireNonNull(target);
    Objects.requireNonNull(resource);
    Objects.requireNonNull(reason);
    Objects.requireNonNull(retry);
  }

  public boolean prerequisite() {
    return action != Action.CONSTRUCTION;
  }
}
