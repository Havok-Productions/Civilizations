package dev.civilizations.core;

import java.util.*;

/** A durable proposal is distinct from executable jobs and from a completed building. */
public record DesignProposal(
    String id,
    String kind,
    String original,
    String blueprint,
    Pos origin,
    String status,
    String reason,
    int attempts,
    long retryAt,
    List<Attempt> history) {
  public record Attempt(String blueprint, String reason, long time) {}

  public DesignProposal {
    history = history == null ? List.of() : List.copyOf(history);
  }

  public DesignProposal(
      String id,
      String kind,
      String original,
      String blueprint,
      Pos origin,
      String status,
      String reason,
      int attempts,
      long retryAt) {
    this(id, kind, original, blueprint, origin, status, reason, attempts, retryAt, List.of());
  }

  public DesignProposal waiting(String state, String why, long retry) {
    return new DesignProposal(
        id, kind, original, blueprint, origin, state, why, attempts + 1, retry, recorded(why));
  }

  public DesignProposal revised(String candidate, String state, String why, long retry) {
    return new DesignProposal(
        id, kind, original, candidate, origin, state, why, attempts, retry, recorded(reason));
  }

  private List<Attempt> recorded(String why) {
    List<Attempt> saved = new ArrayList<>(history);
    // Retain distinct outcomes; repeated polling should not duplicate the same evidence forever.
    saved.removeIf(a -> a.blueprint().equals(blueprint) && a.reason().equals(why));
    saved.add(new Attempt(blueprint, why, System.currentTimeMillis()));
    return List.copyOf(saved);
  }

  public boolean needsSalvage() {
    return status.equals("needs_revision")
        || reason.startsWith("Physical validation:")
        || reason.startsWith("Fresh survey unavailable:")
        || reason.startsWith("Survey could not start:");
  }

  public boolean due(long now) {
    return retryAt <= now;
  }
}
