package dev.civilizations.core;

import java.util.UUID;

public final class Job {
  public enum Kind {
    PLACE,
    CLEAR,
    MINE,
    FARM,
    PATH
  }

  public String id = UUID.randomUUID().toString();
  public Kind kind;
  public String project;
  public Pos target;
  public Pos stand;
  public String material;
  public String expected;
  public String blockData;
  public boolean complete;
  public boolean everBuilt;
  public int failures;
  public int phase;
  public transient String owner;
  public transient long leaseUntil;
  public transient long retryAfter;

  public Job() {}

  public Job(
      Kind kind,
      String project,
      Pos target,
      Pos stand,
      String material,
      String expected,
      String data) {
    this.kind = kind;
    this.project = project;
    this.target = target;
    this.stand = stand;
    this.material = material;
    this.expected = expected;
    this.blockData = data;
  }

  public Job copy() {
    Job j = new Job(kind, project, target, stand, material, expected, blockData);
    j.id = id;
    j.complete = complete;
    j.everBuilt = everBuilt;
    j.failures = failures;
    j.phase = phase;
    return j;
  }
}
