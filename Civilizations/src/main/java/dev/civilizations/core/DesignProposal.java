package dev.civilizations.core;

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
    long retryAt) {
  public DesignProposal waiting(String state, String why, long retry) {
    return new DesignProposal(
        id, kind, original, blueprint, origin, state, why, attempts + 1, retry);
  }

  public boolean due(long now) {
    return !status.equals("needs_revision") && retryAt <= now;
  }
}
