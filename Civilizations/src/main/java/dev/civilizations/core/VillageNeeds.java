package dev.civilizations.core;

import java.util.*;

/** Plain observations for decisions; no imaginary energy system or model-created resources. */
public final class VillageNeeds {
  private List<String> planning = List.of();
  private long lastThreat;

  public synchronized void planning(List<String> messages) {
    planning = List.copyOf(messages);
  }

  public synchronized void threat(long now) {
    lastThreat = now;
  }

  public synchronized Map<String, Object> report(
      Settlement village, Map<String, Integer> inventory, long now) {
    Map<String, Object> out = new LinkedHashMap<>();
    boolean threat = lastThreat > 0 && now - lastThreat < 300_000;
    out.put("recent_hostile_mobs", threat);
    out.put("community_chest_available", village.chest() != null);
    out.put("population", village.population());
    out.put(
        "community_food",
        Map.of(
            "bread",
            village.stock().getOrDefault("BREAD", 0),
            "wheat",
            village.stock().getOrDefault("WHEAT", 0),
            "seeds",
            village.stock().getOrDefault("WHEAT_SEEDS", 0)));
    out.put(
        "food_needed",
        inventory.getOrDefault("BREAD", 0) < 3 && inventory.getOrDefault("WHEAT", 0) < 9);
    out.put("planning_constraints", planning);
    out.put("unfinished_jobs", village.jobs().stream().filter(j -> !j.complete).count());
    out.put(
        "capabilities",
        List.of(
            "repair recorded construction and observed building blocks near village beds",
            "grade one layer of unprotected soil for a validated house",
            "build validated walls and lights",
            "mine dry natural terrain",
            "plant and harvest hydrated wheat plots",
            "craft bread from wheat"));
    out.put(
        "rules",
        "Carry ingredients until the whole assigned project or farming cycle completes. Deposit"
            + " only surplus after completion. No stamina/energy mechanic. Unsafe or unavailable"
            + " sites must be deferred.");
    return out;
  }

  public synchronized boolean danger(long now) {
    return lastThreat > 0 && now - lastThreat < 300_000;
  }

  public synchronized List<String> constraints() {
    return planning;
  }
}
