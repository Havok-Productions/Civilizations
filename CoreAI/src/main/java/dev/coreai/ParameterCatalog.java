package dev.coreai;

import java.util.*;

/** Named controller tuning points. Adding a parameter requires a consumer and outcome coverage. */
public final class ParameterCatalog {
  public record Spec(String section, int minimum, int maximum, int defaultValue, String purpose) {}

  public static final Map<String, Spec> SPECS =
      Map.of(
          "construction.reach_squared",
              new Spec(
                  "construction",
                  4,
                  21,
                  12,
                  "Squared block distance before approaching a work target; physical reach and"
                      + " visibility still apply"),
          "construction.interval_ms",
              new Spec(
                  "construction",
                  250,
                  10000,
                  1000,
                  "Delay between physical work actions; lower is faster"),
          "construction.face_ms",
              new Spec(
                  "construction",
                  250,
                  1000,
                  250,
                  "Visible time facing a work target before changing it"),
          "navigation.transition_ms",
              new Spec(
                  "navigation",
                  3000,
                  20000,
                  10000,
                  "Time allowed for a native route transition before trying another"),
          "navigation.recovery_attempts",
              new Spec(
                  "navigation",
                  2,
                  12,
                  6,
                  "Failed route transitions before asking for a recovery program"),
          "recovery.instruction_ms",
              new Spec(
                  "recovery", 3000, 30000, 12000, "Time allowed per executable skill instruction"),
          "observation.probe_limit",
              new Spec(
                  "observation",
                  4,
                  32,
                  12,
                  "Distinct observed block states included in a recovery request"));

  private ParameterCatalog() {}

  public static void validate(String key, int value) {
    Spec spec = SPECS.get(key);
    if (spec == null) throw new IllegalArgumentException("Unknown tuning parameter: " + key);
    if (value < spec.minimum() || value > spec.maximum())
      throw new IllegalArgumentException(
          key + " must be " + spec.minimum() + ".." + spec.maximum());
  }
}
