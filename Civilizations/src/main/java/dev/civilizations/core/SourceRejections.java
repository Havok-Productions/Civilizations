package dev.civilizations.core;

import java.util.*;
import java.util.function.Supplier;

/** Actor-local physical source evidence; a changed observation immediately permits another try. */
public final class SourceRejections {
  private record Key(String resource, Pos position) {}

  private record Failure(String observation, String reason) {}

  private final Map<Key, Failure> failures = new LinkedHashMap<>();

  public void reject(String resource, Pos position, String observation, String reason) {
    failures.put(new Key(resource, position), new Failure(observation, reason));
    // Memory eviction only permits re-observation; it never permanently excludes a source.
    if (failures.size() > 256) failures.remove(failures.keySet().iterator().next());
  }

  public boolean unchanged(String resource, Pos position, Supplier<String> observation) {
    Key key = new Key(resource, position);
    Failure failure = failures.get(key);
    if (failure == null) return false;
    String current = observation.get();
    if (current == null) return false; // Unowned regions cannot establish unchanged terrain.
    if (failure.observation.equals(current)) return true;
    failures.remove(key);
    return false;
  }

  public Map<String, Long> reasons() {
    return failures.values().stream()
        .collect(
            java.util.stream.Collectors.groupingBy(
                Failure::reason, java.util.TreeMap::new, java.util.stream.Collectors.counting()));
  }
}
