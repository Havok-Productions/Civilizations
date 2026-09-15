package dev.civilizations.design;

import com.google.gson.Gson;
import dev.civilizations.core.*;
import java.util.*;

/** Revision of a retained intention, with the original goal and proposal identity kept intact. */
public final class ProposalSalvage {
  private static final Gson JSON = new Gson();

  private ProposalSalvage() {}

  public static Map<String, Object> context(DesignProposal proposal) {
    return Map.of(
        "proposal_id",
        proposal.id(),
        "original",
        Blueprint.parse(proposal.original()),
        "latest_attempt",
        Blueprint.parse(proposal.blueprint()),
        "failure",
        proposal.reason(),
        "survey_origin",
        proposal.origin(),
        "previous_attempts",
        proposal.history().stream().skip(Math.max(0, proposal.history().size() - 6)).toList(),
        "instruction",
        "Salvage this existing proposal and its village goal. Explain the recorded failure, then"
            + " return a revised blueprint of the same kind. Keep usable parts. You may change the"
            + " location, route, entrance, dimensions or a buildable first stage. Natural clutter,"
            + " trees and soil can produce real preparation jobs when the executor finds safe"
            + " reachable clearance. Use the fresh map and actual protected blocks; do not assume"
            + " an unknown area is empty. Previously failed variants are evidence to improve upon."
            + " If observation capacity was exhausted, use a meaningful local stage or correct"
            + " explicitly declared coordinate space. Do not replace this with an unrelated village"
            + " goal. Return wait only if you cannot identify a useful revision.");
  }

  /** Returns the new state even for an invalid revision, so an attempted repair is never lost. */
  public static DesignProposal revise(
      DesignProposal saved, Blueprint proposed, String source, long now) {
    if (!saved.kind().equals(proposed.kind()))
      return saved.waiting(
          "needs_revision",
          "Salvage returned "
              + proposed.kind()
              + " instead of the retained "
              + saved.kind()
              + " goal",
          now);
    Blueprint original = Blueprint.parse(saved.original());
    Blueprint candidate =
        new Blueprint(
            proposed.kind(),
            original.purpose(),
            proposed.x(),
            proposed.z(),
            proposed.width(),
            proposed.depth(),
            proposed.height(),
            proposed.direction(),
            proposed.points());
    String reason = source, status = "awaiting_validation";
    try {
      var prepared = BlueprintNormalization.prepare(candidate);
      candidate = prepared.blueprint();
      candidate.validateGeometry();
      if (!prepared.adjustments().isEmpty())
        reason += "; " + String.join("; ", prepared.adjustments());
    } catch (IllegalArgumentException e) {
      status = "needs_revision";
      reason += "; Invalid geometry: " + e.getMessage();
    }
    return saved.revised(JSON.toJson(candidate), status, reason, now);
  }

  public static boolean unchanged(DesignProposal saved, Blueprint proposal) {
    Blueprint prior = Blueprint.parse(saved.blueprint());
    return prior.kind().equals(proposal.kind())
        && prior.x() == proposal.x()
        && prior.z() == proposal.z()
        && prior.width() == proposal.width()
        && prior.depth() == proposal.depth()
        && prior.height() == proposal.height()
        && prior.direction().equals(proposal.direction())
        && prior.points().equals(proposal.points());
  }

  public static boolean usable(DesignProposal saved, Blueprint response) {
    if (response == null || !response.kind().equals(saved.kind())) return false;
    try {
      Blueprint prepared = BlueprintNormalization.prepare(response).blueprint();
      prepared.validateGeometry();
      return !unchanged(saved, prepared);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  public static boolean geometryValid(DesignProposal saved) {
    try {
      Blueprint.parse(saved.blueprint()).validateGeometry();
      return true;
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  /** These examples already passed the compiler on this survey; fresh admission still follows. */
  public static Blueprint alternative(DesignProposal saved, List<Map<String, Object>> examples) {
    Blueprint prior = Blueprint.parse(saved.blueprint());
    return examples.stream()
        .map(e -> (Blueprint) e.get("blueprint"))
        .filter(b -> b.kind().equals(saved.kind()) && !unchanged(saved, b))
        .min(Comparator.comparingLong(b -> difference(prior, b)))
        .orElse(null);
  }

  private static long difference(Blueprint a, Blueprint b) {
    return Math.abs((long) a.x() - b.x())
        + Math.abs((long) a.z() - b.z())
        + Math.abs((long) a.width() - b.width())
        + Math.abs((long) a.depth() - b.depth())
        + Math.abs((long) a.height() - b.height());
  }
}
