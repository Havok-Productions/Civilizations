package dev.civilizations.core;

import java.util.*;

/** Persisted design and material estimate; completion is determined from its actual jobs. */
public record DesignRecord(
    String project,
    String kind,
    String purpose,
    String blueprint,
    Map<String, Integer> materials,
    int jobs,
    long created,
    Pos origin) {
  public DesignRecord(
      String project,
      String kind,
      String purpose,
      String blueprint,
      Map<String, Integer> materials,
      int jobs,
      long created) {
    this(project, kind, purpose, blueprint, materials, jobs, created, null);
  }
}
