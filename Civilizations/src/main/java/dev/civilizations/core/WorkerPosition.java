package dev.civilizations.core;

import java.util.*;

/** Immutable position captured on an entity's owning region, for cross-region selection only. */
public record WorkerPosition(String worker, UUID world, double x, double y, double z) {
  public double distanceSquared(WorkerPosition other) {
    if (!world.equals(other.world)) return Double.POSITIVE_INFINITY;
    double dx = x - other.x, dy = y - other.y, dz = z - other.z;
    return dx * dx + dy * dy + dz * dz;
  }

  public static Optional<WorkerPosition> nearest(
      WorkerPosition origin, Collection<WorkerPosition> candidates) {
    return candidates.stream()
        .filter(Objects::nonNull)
        .filter(p -> origin.world.equals(p.world))
        .min(
            Comparator.comparingDouble(origin::distanceSquared)
                .thenComparing(WorkerPosition::worker));
  }
}
