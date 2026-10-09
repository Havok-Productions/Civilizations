package dev.coreai.movement;

import java.util.*;

/** Pre-action numeric observations and a separately measured terminal label. */
public record MovementSample(
    String id,
    String group,
    String family,
    String source,
    List<Double> features,
    Boolean success,
    String reason,
    long elapsedMillis) {
  public static final List<String> FEATURES =
      List.of(
          "length",
          "max_rise",
          "max_drop",
          "min_headroom",
          "body_blocked_fraction",
          "supported_fraction",
          "water_fraction",
          "unknown_fraction",
          "hazard_fraction");

  public MovementSample {
    features = List.copyOf(features);
    if (features.size() != FEATURES.size() || features.stream().anyMatch(v -> !Double.isFinite(v)))
      throw new IllegalArgumentException("Invalid movement features");
    if (!Set.of("virtual", "physical").contains(source) || group == null || group.isBlank())
      throw new IllegalArgumentException("Missing movement provenance");
  }

  public double[] inputs() {
    double[] scales = {8, 2, 4, 3, 1, 1, 1, 1, 1};
    double[] result = new double[features.size()];
    for (int i = 0; i < result.length; i++)
      result[i] = Math.clamp(features.get(i) / scales[i], -4, 4);
    return result;
  }
}
