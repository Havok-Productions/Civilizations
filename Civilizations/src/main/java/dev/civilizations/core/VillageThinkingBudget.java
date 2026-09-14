package dev.civilizations.core;

import java.util.concurrent.ConcurrentHashMap;

/** Nearby stalled workers share one recovery opportunity instead of flooding the model. */
public final class VillageThinkingBudget {
  private final ConcurrentHashMap<String, Long> next = new ConcurrentHashMap<>();

  public synchronized boolean take(String village, long now, long cooldown) {
    if (now < next.getOrDefault(village, 0L)) return false;
    next.put(village, now + cooldown);
    return true;
  }
}
