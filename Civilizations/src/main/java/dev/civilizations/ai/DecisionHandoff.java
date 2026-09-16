package dev.civilizations.ai;

import java.util.Objects;

/** Advice belongs to the goal/observation that requested it, not a later commitment. */
public record DecisionHandoff(Decision decision, String project, long expiresAt) {
  public Decision current(String currentProject, long now) {
    return now <= expiresAt && Objects.equals(project, currentProject) ? decision : null;
  }
}
