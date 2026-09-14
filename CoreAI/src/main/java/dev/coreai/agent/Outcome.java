package dev.coreai.agent;

import java.util.*;

/** Success means the host verified the goal state; submitting a plan is not success. */
public record Outcome(Status status, String reason, Facts evidence) {
  public enum Status {
    SUCCEEDED,
    FAILED,
    CANCELLED
  }

  public Outcome {
    Objects.requireNonNull(status);
    if (reason == null || reason.isBlank())
      throw new IllegalArgumentException("Outcome reason required");
    Objects.requireNonNull(evidence);
    if (status == Status.SUCCEEDED && evidence.object().isEmpty())
      throw new IllegalArgumentException("Verified success needs executor evidence");
  }

  public static Outcome cancelled(String reason) {
    return new Outcome(Status.CANCELLED, reason, Facts.of(Map.of()));
  }
}
