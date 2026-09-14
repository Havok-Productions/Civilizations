package dev.civilizations.learning;

import static dev.coreai.PolicyCase.features;

import dev.coreai.*;
import java.util.*;

/** Immutable host expectations. Teachers cannot change the evaluator or its labels. */
public final class VillagerPolicyCases {
  private VillagerPolicyCases() {}

  private static PolicyCase pair(
      String id, Map<String, Double> a, Map<String, Double> b, String expected) {
    return new PolicyCase(
        id,
        "host design expectation, not a world execution",
        List.of(new PolicyCase.Option("a", a), new PolicyCase.Option("b", b)),
        expected);
  }

  public static List<PolicyCase> jobs() {
    return List.of(
        pair("repair before expansion", features(0, "repair", 1), features(100), "a"),
        pair("continue healthy project", features(0, "continuing", 1), features(100), "a"),
        pair("keep ordinary order", features(0), features(100), "a"),
        pair("avoid repeatedly failed work", features(0, "failures", 3), features(100), "b"),
        pair("prefer ready equal-priority work", features(0, "missing", 4), features(100), "b"),
        pair(
            "repair with equal missing ingredients",
            features(0, "repair", 1, "missing", 2),
            features(100, "missing", 2),
            "a"));
  }

  public static List<PolicyCase> routes() {
    return List.of(
        pair("ordinary route order", features(0), features(10), "a"),
        pair("prefer flat comparable approach", features(0, "vertical", 2), features(10), "b"),
        pair(
            "do not take large detour for flatness",
            features(0, "vertical", 1),
            features(1000, "detour", 100),
            "a"),
        pair(
            "equally high keep shorter route",
            features(0, "vertical", 2),
            features(10, "vertical", 2),
            "a"));
  }
}
