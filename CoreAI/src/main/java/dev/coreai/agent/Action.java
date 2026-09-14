package dev.coreai.agent;

import java.util.Objects;

/** A host-supported action; a reasoning proposal becomes actionable after host validation. */
public record Action(String id, String goal, String capability, Facts arguments) {
  public Action {
    if (id == null
        || id.isBlank()
        || goal == null
        || goal.isBlank()
        || capability == null
        || capability.isBlank())
      throw new IllegalArgumentException("Action, goal and capability identities required");
    Objects.requireNonNull(arguments);
  }
}
