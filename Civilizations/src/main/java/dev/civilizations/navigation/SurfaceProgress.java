package dev.civilizations.navigation;

import dev.civilizations.core.Pos;
import java.util.*;

/** Measured approach progress, independent of route refreshes and vertical water bobbing. */
public final class SurfaceProgress {
  public record Edge(Pos from, Pos to) {}

  private record Attempt(double best, long improved) {}

  private final Map<Edge, Attempt> attempts = new HashMap<>();
  private final Map<Edge, String> failures = new LinkedHashMap<>();

  public boolean stalled(Edge edge, double horizontalDistance, long now, long timeout) {
    Attempt old = attempts.get(edge);
    if (old == null || horizontalDistance < old.best() - .1) {
      attempts.put(edge, new Attempt(horizontalDistance, now));
      return false;
    }
    return now - old.improved() >= timeout;
  }

  public void reject(Edge edge, String signature) {
    failures.put(edge, signature);
    attempts.remove(edge);
  }

  public boolean blocked(Edge edge, String currentSignature) {
    String old = failures.get(edge);
    if (old == null) return false;
    if (old.equals(currentSignature)) return true;
    failures.remove(edge);
    attempts.remove(edge);
    return false;
  }

  public boolean remembered(Edge edge) {
    return failures.containsKey(edge);
  }

  public void reached(Pos target) {
    attempts.keySet().removeIf(edge -> edge.to().equals(target));
  }

  public void reset() {
    attempts.clear();
    failures.clear();
  }
}
