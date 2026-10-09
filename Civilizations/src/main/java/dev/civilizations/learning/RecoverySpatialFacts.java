package dev.civilizations.learning;

import dev.civilizations.core.Pos;
import dev.civilizations.navigation.NavigationMap;
import java.util.*;

/** Independent occupancy, support and mutation facts; no claim of route reachability. */
public final class RecoverySpatialFacts {
  private RecoverySpatialFacts() {}

  public static Map<String, Object> at(NavigationMap map, Pos origin, Pos feet) {
    var body = map.cell(feet);
    var head = map.cell(feet.add(0, 1, 0));
    var floor = map.cell(feet.add(0, -1, 0));
    var passage = map.passage(feet, false);
    var result = new LinkedHashMap<String, Object>();
    result.put(
        "relative", new Pos(feet.x() - origin.x(), feet.y() - origin.y(), feet.z() - origin.z()));
    result.put("body", body);
    result.put("head", head);
    result.put("floor", floor);
    result.put("body_empty", body.kind() == NavigationMap.Kind.AIR);
    result.put("head_empty", head.kind() == NavigationMap.Kind.AIR);
    result.put(
        "support_present",
        floor.kind() == NavigationMap.Kind.SOLID || floor.kind() == NavigationMap.Kind.SOFT);
    result.put("standing_admissible_in_snapshot", passage.allowed());
    result.put("standing_failure", passage.reason());
    result.put("route_reachable", "not_established_by_standing_test");
    result.put(
        "clear_body_useful",
        body.kind() != NavigationMap.Kind.AIR && body.kind() != NavigationMap.Kind.UNKNOWN);
    result.put("mutation_reserved", body.removal().equals("protected_or_reserved"));
    result.put(
        "interpretation",
        "Reservation restricts edits, not occupying empty space. Empty feet/head do not imply"
            + " supported ground or a connected route. Never CLEAR air to fix missing support.");
    return Map.copyOf(result);
  }
}
