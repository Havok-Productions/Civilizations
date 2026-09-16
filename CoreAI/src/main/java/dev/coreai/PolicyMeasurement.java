package dev.coreai;

/**
 * Host-measured cost for comparable executed work; inconclusive observations stay in the journal.
 */
public record PolicyMeasurement(
    String context, double cost, boolean relevant, boolean success, String reason) {
  public PolicyMeasurement {
    if (relevant && (context == null || context.isBlank() || !Double.isFinite(cost) || cost <= 0))
      throw new IllegalArgumentException(
          "Comparable observations require a context and positive finite cost");
  }

  public static PolicyMeasurement ignored(String reason) {
    return new PolicyMeasurement("", 0, false, false, reason);
  }
}
