package dev.civilizations.learning;

import dev.coreai.*;
import java.util.*;

/** Separates executed progress from no-ops, interruptions, and failures with an unknown cause. */
public final class PolicyEvidence {
  private PolicyEvidence() {}

  public static PolicyMeasurement measure(
      CoreAiCoordinator.Ticket ticket, boolean success, Map<String, ?> evidence, long now) {
    if (ticket.choice().version().equals("host-or-model-override"))
      return PolicyMeasurement.ignored("choice overridden");
    if (evidence.containsKey("interruption") || Boolean.FALSE.equals(evidence.get("attributable")))
      return PolicyMeasurement.ignored("external interruption");
    String effect = String.valueOf(evidence.get("executor_result"));
    if (effect.equals("already_satisfied"))
      return PolicyMeasurement.ignored("already-satisfied work");
    if (!success && !Boolean.TRUE.equals(evidence.get("policy_fault")))
      return PolicyMeasurement.ignored("failure cause is not established");
    if (success && !Set.of("world_changed", "reached planned terrain-route step").contains(effect))
      return PolicyMeasurement.ignored("no verified physical progress");
    double units = 1;
    if (ticket.choice().scope() == CoreAiCoordinator.Scope.ROUTES)
      units =
          Math.max(1, Math.sqrt(ticket.choice().options().getFirst().features().get("distance")));
    return new PolicyMeasurement(
        ticket.context(),
        Math.max(1, now - ticket.startedAt()) / units,
        true,
        success,
        "elapsed milliseconds per verified work unit; matched observations, not a counterfactual"
            + " proof");
  }

  public static String context(CoreAiCoordinator.Choice choice, String work) {
    // Comparable option sets, independent of UUIDs and list order. Keep requirement/danger classes.
    var classes =
        choice.options().stream()
            .map(
                o -> {
                  var f = o.features();
                  return List.of(
                          f.get("repair"),
                          f.get("food"),
                          f.get("danger"),
                          f.get("continuing"),
                          Math.min(3, f.get("missing")),
                          Math.min(3, f.get("vertical")),
                          Math.floor(Math.sqrt(Math.max(0, f.get("distance"))) / 4),
                          f.get("detour"),
                          f.get("failures"))
                      .toString();
                })
            .sorted()
            .toList();
    return choice.scope() + ":" + work + ":" + classes;
  }
}
