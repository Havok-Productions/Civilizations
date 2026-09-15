package dev.civilizations.core;

/** Stable job ids survive releases, sleep, restarts and settlement merges. */
public record TaskCheckpoint(String job, String parent, String reason, long updated) {
  public String root() {
    return parent == null || parent.isEmpty() ? job : parent;
  }
}
