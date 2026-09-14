package dev.coreai.agent;

import java.util.Objects;

public record Observation(String agent, String environment, long observedAt, Facts facts) {
  public Observation {
    if (agent == null || agent.isBlank() || environment == null || environment.isBlank())
      throw new IllegalArgumentException("Observation needs agent and environment identities");
    Objects.requireNonNull(facts);
  }
}
