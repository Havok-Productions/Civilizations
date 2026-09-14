package dev.civilizations.core;

import java.util.*;

/** Shared, bounded facts; suggestions are never promoted into observations. */
public final class VillageKnowledge {
  public record Blockage(String key, String reason, long until) {}

  public record Supply(String material, String project, long observedAt) {}

  public record Progress(
      String goal, String step, String blocker, String lastResult, long updatedAt) {}

  private final Map<String, Blockage> blocked = new LinkedHashMap<>();
  private final Map<String, Supply> supplies = new LinkedHashMap<>();
  private final Map<String, Progress> workers = new LinkedHashMap<>();

  public synchronized void block(String key, String reason, long now, long duration) {
    blocked.entrySet().removeIf(e -> e.getValue().until() <= now);
    blocked.put(key, new Blockage(key, shortText(reason), now + duration));
    trim(blocked, 128);
  }

  public synchronized boolean blocked(String key, long now) {
    Blockage b = blocked.get(key);
    return b != null && b.until() > now;
  }

  public synchronized void clear(String key) {
    blocked.remove(key);
  }

  public synchronized void need(String material, String project, long now) {
    supplies.put(material + ":" + project, new Supply(material, project, now));
    trim(supplies, 32);
  }

  public synchronized List<Supply> supplies(Set<String> unfinished, long now) {
    supplies
        .values()
        .removeIf(s -> !unfinished.contains(s.project()) || now - s.observedAt() > 600_000);
    return List.copyOf(supplies.values());
  }

  public synchronized void progress(
      String worker, String goal, String step, String blocker, String result, long now) {
    Progress old = workers.get(worker);
    workers.put(
        worker,
        new Progress(
            shortText(goal),
            shortText(step),
            shortText(blocker),
            result.isEmpty() && old != null ? old.lastResult() : shortText(result),
            now));
    trim(workers, 32);
  }

  public synchronized Progress worker(String id) {
    return workers.get(id);
  }

  public synchronized Map<String, Object> report(long now) {
    return Map.of(
        "blocked",
        blocked.values().stream().filter(b -> b.until() > now).limit(8).toList(),
        "workers",
        workers.entrySet().stream()
            .limit(5)
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
  }

  public synchronized List<Blockage> blocks() {
    return List.copyOf(blocked.values());
  }

  public synchronized List<Supply> supplySnapshot() {
    return List.copyOf(supplies.values());
  }

  public synchronized Map<String, Progress> progressSnapshot() {
    return Map.copyOf(workers);
  }

  public synchronized void restore(List<Blockage> b, List<Supply> s, Map<String, Progress> p) {
    long now = System.currentTimeMillis();
    if (b != null)
      b.stream()
          .filter(v -> v.until() > now && v.until() <= now + 300_000)
          .limit(128)
          .forEach(v -> blocked.put(v.key(), v));
    if (s != null)
      s.stream().limit(32).forEach(v -> supplies.put(v.material() + ":" + v.project(), v));
    if (p != null)
      p.entrySet().stream().limit(32).forEach(e -> workers.put(e.getKey(), e.getValue()));
  }

  private static String shortText(String text) {
    return text.substring(0, Math.min(240, text.length()));
  }

  private static void trim(Map<?, ?> values, int limit) {
    while (values.size() > limit) values.remove(values.keySet().iterator().next());
  }
}
