package dev.civilizations.design;

import com.google.gson.Gson;
import dev.civilizations.core.*;
import dev.civilizations.world.Terrain;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;

/** Durable architect intentions, separate from admitted construction and its physical receipts. */
public final class DesignProposals {
  private static final Gson JSON = new Gson();

  private DesignProposals() {}

  public record Admission(
      DesignProposal proposal, DesignRecord design, DesignCompiler.Result compiled) {
    public boolean accepted() {
      return design != null;
    }
  }

  public record Trial(Admission admission, List<String> warnings) {}

  /**
   * Explicit trial bypasses need/capacity policy, retaining block and resource execution checks.
   */
  public static Trial trial(
      Settlement v, DesignProposal p, Terrain terrain, Predicate<Pos> occupied, long now) {
    var warnings = new ArrayList<String>();
    warnings.add("Original rejection: " + p.reason());
    warnings.add("Admin trial overrides village-need and active-project-slot policy");
    String project = project(p);
    for (DesignRecord record : v.designs())
      if (record.project().equals(project) || record.project().startsWith(project + "@"))
        return new Trial(new Admission(p, record, null), List.copyOf(warnings));
    try {
      Blueprint blueprint = Blueprint.parse(p.blueprint());
      List<Pos> landmarks = new ArrayList<>(v.beds());
      landmarks.addAll(v.chests());
      var compiled =
          new DesignCompiler()
              .trial(blueprint, terrain, p.origin(), project, occupied, landmarks, warnings);
      var record =
          new DesignRecord(
              project,
              blueprint.kind(),
              blueprint.purpose(),
              p.blueprint(),
              compiled.materials(),
              compiled.jobs().size(),
              now,
              p.origin());
      if (!v.addDesign(
          record,
          compiled.jobs(),
          compiled.reservations(),
          compiled.construction(),
          Integer.MAX_VALUE))
        throw new IllegalArgumentException(
            "Village paused, retired, or construction space already reserved");
      v.acceptedProposal(p.id());
      return new Trial(new Admission(p, record, compiled), List.copyOf(warnings));
    } catch (IllegalArgumentException | ArithmeticException error) {
      var waiting =
          defer(v, p, "Trial could not produce executable jobs: " + error.getMessage(), now + 5000);
      return new Trial(new Admission(waiting, null, null), List.copyOf(warnings));
    }
  }

  public static DesignProposal retain(Settlement v, Blueprint original, Pos origin, long now) {
    Blueprint candidate = original;
    String status = "awaiting_validation", reason = "Awaiting fresh physical observations";
    try {
      var normalized = BlueprintNormalization.prepare(original);
      candidate = normalized.blueprint();
      candidate.validateGeometry();
      if (!normalized.adjustments().isEmpty()) reason = String.join("; ", normalized.adjustments());
    } catch (IllegalArgumentException e) {
      status = "needs_revision";
      reason =
          "Invalid geometry: "
              + e.getMessage()
              + "; revise this retained proposal using explicit relative or world coordinates";
    }
    // Purpose wording is not a new project. A reworded retry must not multiply identical builds.
    Blueprint geometry =
        new Blueprint(
            candidate.kind(),
            "",
            candidate.x(),
            candidate.z(),
            candidate.width(),
            candidate.depth(),
            candidate.height(),
            candidate.direction(),
            candidate.points());
    String id =
        UUID.nameUUIDFromBytes(
                (origin.key() + ":" + JSON.toJson(geometry)).getBytes(StandardCharsets.UTF_8))
            .toString();
    for (DesignProposal existing : v.proposals()) if (existing.id().equals(id)) return existing;
    DesignProposal proposal =
        new DesignProposal(
            id,
            candidate.kind(),
            JSON.toJson(original),
            JSON.toJson(candidate),
            origin,
            status,
            reason,
            0,
            now);
    v.proposal(proposal);
    return proposal;
  }

  public static DesignProposal defer(Settlement v, DesignProposal p, String reason, long retryAt) {
    DesignProposal waiting = p.waiting("waiting", reason, retryAt);
    v.proposal(waiting);
    return waiting;
  }

  public static String project(DesignProposal p) {
    return "design-" + p.kind() + "-" + p.id();
  }

  /** Called under the village connection guard, using a fresh immutable terrain snapshot. */
  public static Admission admit(
      Settlement v,
      DesignProposal p,
      Terrain terrain,
      Predicate<Pos> occupied,
      int activeLimit,
      long retryAt) {
    String project = project(p);
    // A callback may finish after a merge or a previous acceptance; never duplicate its jobs.
    for (DesignRecord record : v.designs())
      if (record.project().equals(project) || record.project().startsWith(project + "@")) {
        v.acceptedProposal(p.id());
        return new Admission(p, record, null);
      }
    if (p.status().equals("needs_revision")) return new Admission(p, null, null);
    if (!DesignNeeds.allowed(v, activeLimit).contains(p.kind()))
      return new Admission(
          defer(
              v,
              p,
              "Waiting for village need or an active project slot; proposal retained",
              retryAt),
          null,
          null);
    try {
      Blueprint blueprint = Blueprint.parse(p.blueprint());
      List<Pos> landmarks = new ArrayList<>(v.beds());
      landmarks.addAll(v.chests());
      DesignCompiler.Result compiled =
          new DesignCompiler()
              .compile(blueprint, terrain, p.origin(), project, occupied, landmarks);
      DesignRecord record =
          new DesignRecord(
              project,
              blueprint.kind(),
              blueprint.purpose(),
              p.blueprint(),
              compiled.materials(),
              compiled.jobs().size(),
              System.currentTimeMillis(),
              p.origin());
      if (!v.addDesign(
          record,
          compiled.jobs(),
          compiled.reservations(),
          compiled.construction(),
          activeLimit + (p.kind().equals("mine") ? 1 : 0)))
        return new Admission(
            defer(
                v,
                p,
                "Space or project capacity changed before admission; proposal retained",
                retryAt),
            null,
            null);
      v.acceptedProposal(p.id());
      return new Admission(p, record, compiled);
    } catch (IllegalArgumentException | ArithmeticException e) {
      return new Admission(
          defer(
              v,
              p,
              "Physical validation: "
                  + e.getMessage()
                  + "; retry with fresh observations or revise the retained layout",
              retryAt),
          null,
          null);
    }
  }
}
