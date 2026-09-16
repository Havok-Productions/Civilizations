package dev.civilizations.design;

import dev.civilizations.core.*;
import dev.civilizations.world.Terrain;
import java.util.*;

/** Explains a rejected proposal without confusing candidate checks with physical worker trials. */
public final class DesignDiagnostics {
  private DesignDiagnostics() {}

  public static Map<String, Object> rejected(
      Settlement village, DesignProposal proposal, String phase, String reason, Terrain terrain) {
    var report = new LinkedHashMap<String, Object>();
    report.put("stage", "proposal_rejected");
    report.put("phase", phase);
    report.put("proposal_id", proposal.id());
    report.put("kind", proposal.kind());
    report.put("reason", reason);
    report.put("category", category(reason));
    report.put("blueprint_origin", proposal.origin());
    report.put("inhabited_focus", DesignFocus.select(village));
    report.put("blueprint", proposal.blueprint());
    report.put("execution_started", false);
    report.put("prior_attempts", proposal.attempts());
    report.put("next_action", next(category(reason)));
    if (terrain != null) report.put("observation_coverage", terrain.observationReport());
    try {
      var area =
          LocalBlueprints.footprint(Blueprint.parse(proposal.blueprint()), proposal.origin());
      var anchor =
          LocalBlueprints.anchors(village, proposal.origin()).stream()
              .min(Comparator.comparingDouble(area::distance))
              .orElse(proposal.origin());
      report.put("world_footprint", area);
      report.put("nearest_village_anchor", anchor);
      report.put("distance_to_village_blocks", area.distance(anchor));
    } catch (RuntimeException malformed) {
      report.put(
          "footprint_error",
          malformed.getMessage() == null ? malformed.toString() : malformed.getMessage());
    }
    return Collections.unmodifiableMap(report);
  }

  private static String category(String reason) {
    String r = reason.toLowerCase(Locale.ROOT);
    if (r.contains("coordinate") || r.contains("encloses none")) return "location_or_purpose";
    if (r.contains("capacity") || r.contains("budget")) return "planning_capacity";
    if (r.contains("unobserved")
        || r.contains("not observed")
        || r.contains("unknown")
        || r.contains("snapshot")) return "observation";
    if (r.contains("protected") || r.contains("reserved")) return "occupied_or_protected";
    if (r.contains("support")
        || r.contains("foundation")
        || r.contains("ground")
        || r.contains("grading")
        || r.contains("water")) return "terrain_or_support";
    if (r.contains("approach")
        || r.contains("access")
        || r.contains("disconnected")
        || r.contains("reach")) return "access";
    if (r.contains("geometry")
        || r.contains("gate")
        || r.contains("polygon")
        || r.contains("segment")) return "geometry";
    if (r.contains("slot") || r.contains("village need")) return "village_policy";
    return "other";
  }

  private static String next(String category) {
    return switch (category) {
      case "location_or_purpose" ->
          "Recover coordinate space or translate the retained shape near its village goal; resurvey"
              + " locally";
      case "observation" ->
          "Observe the actual local footprint; unknown cells are not empty ground";
      case "terrain_or_support" ->
          "Inspect the named support block and neighbors; prepare, supply support, or revise the"
              + " local contour";
      case "occupied_or_protected" ->
          "Route around the recorded structure/reservation without losing the village goal";
      case "access" ->
          "Try local approach recovery or an explicit probe trial of the access prediction";
      case "planning_capacity" ->
          "Keep the goal and generate a local buildable stage or smaller survey";
      case "village_policy" -> "Recheck need/active work, or use an explicit construction trial";
      case "geometry" -> "Repair the retained contour, dimensions or entrance and resurvey";
      default -> "Use the exact recorded failure to revise the retained proposal";
    };
  }
}
