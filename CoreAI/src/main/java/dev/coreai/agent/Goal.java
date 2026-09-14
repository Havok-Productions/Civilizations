package dev.coreai.agent;

import java.util.Objects;

/** The host describes an observed need and the state that would satisfy it. */
public record Goal(String id, String description, Facts desiredState) {
  public Goal {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("Goal identity required");
    Objects.requireNonNull(description);
    Objects.requireNonNull(desiredState);
  }
}
