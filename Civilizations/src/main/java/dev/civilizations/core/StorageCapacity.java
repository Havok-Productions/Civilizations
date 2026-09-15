package dev.civilizations.core;

import java.util.*;

/** Capacity evidence expires; an unloaded chest is never assumed full. */
public final class StorageCapacity {
  private record Observation(boolean spareSlot, Set<String> partialStacks, long at) {}

  private final Map<Pos, Observation> observations = new HashMap<>();
  private boolean requested;
  private final Set<String> materials = new HashSet<>();

  public synchronized void request() {
    requested = true;
  }

  public synchronized void request(Map<String, Integer> items) {
    requested = true;
    items.forEach(
        (m, n) -> {
          if (n > 0) materials.add(m);
        });
  }

  public synchronized void fulfilled() {
    requested = false;
    materials.clear();
  }

  public synchronized void observe(Pos p, boolean spareSlot, long now) {
    observe(p, spareSlot, Set.of(), now);
  }

  public synchronized void observe(Pos p, boolean spareSlot, Set<String> partialStacks, long now) {
    observations.put(p, new Observation(spareSlot, Set.copyOf(partialStacks), now));
  }

  public synchronized boolean needsExpansion(List<Pos> stores, long now) {
    return requested
        && !stores.isEmpty()
        && stores.stream()
            .allMatch(
                p -> {
                  Observation o = observations.get(p);
                  return o != null
                      && now - o.at() < 30_000
                      && !o.spareSlot()
                      && Collections.disjoint(materials, o.partialStacks());
                });
  }
}
