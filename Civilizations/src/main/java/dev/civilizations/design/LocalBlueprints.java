package dev.civilizations.design;

import com.google.gson.Gson;
import dev.civilizations.core.*;
import java.util.*;

/** Keeps a model's shape and goal while repairing a misplaced village construction site. */
public final class LocalBlueprints {
  private LocalBlueprints() {}

  public record Footprint(long minX, long minZ, long maxX, long maxZ) {
    double distance(Pos p) {
      return Math.hypot(
          Math.max(0, Math.max(minX - p.x(), p.x() - maxX)),
          Math.max(0, Math.max(minZ - p.z(), p.z() - maxZ)));
    }

    long centerX() {
      return Math.floorDiv(minX + maxX, 2);
    }

    long centerZ() {
      return Math.floorDiv(minZ + maxZ, 2);
    }
  }

  public record Correction(Blueprint blueprint, String reason, Footprint before, Footprint after) {
    public boolean changed() {
      return !reason.isEmpty();
    }
  }

  public static Footprint footprint(Blueprint b, Pos origin) {
    List<Blueprint.Point> points = new ArrayList<>();
    if (Set.of("wall", "path", "lights").contains(b.kind())) points.addAll(b.points());
    else {
      points.add(new Blueprint.Point(b.x(), b.z()));
      long x = b.x(), z = b.z();
      if (b.kind().equals("mine")) {
        long length = (long) b.depth() + b.width();
        x += b.direction().equals("east") ? length : b.direction().equals("west") ? -length : 0;
        z += b.direction().equals("south") ? length : b.direction().equals("north") ? -length : 0;
      } else {
        x += Math.max(0L, (long) b.width() - 1);
        z += Math.max(0L, (long) b.depth() - 1);
      }
      return new Footprint(
          origin.x() + Math.min(b.x(), x),
          origin.z() + Math.min(b.z(), z),
          origin.x() + Math.max(b.x(), x),
          origin.z() + Math.max(b.z(), z));
    }
    if (points.isEmpty()) points.add(new Blueprint.Point(b.x(), b.z()));
    return new Footprint(
        origin.x() + points.stream().mapToLong(Blueprint.Point::x).min().orElseThrow(),
        origin.z() + points.stream().mapToLong(Blueprint.Point::z).min().orElseThrow(),
        origin.x() + points.stream().mapToLong(Blueprint.Point::x).max().orElseThrow(),
        origin.z() + points.stream().mapToLong(Blueprint.Point::z).max().orElseThrow());
  }

  public static List<Pos> anchors(Settlement village, Pos origin) {
    var anchors = new LinkedHashSet<Pos>();
    anchors.addAll(village.beds());
    anchors.addAll(village.chests());
    var positions = village.snapshot().memberPositions;
    village.members().stream().map(positions::get).filter(Objects::nonNull).forEach(anchors::add);
    anchors.add(DesignFocus.select(village));
    // A valid saved local origin remains a useful focus even if no worker sample exists yet.
    if (positions.isEmpty()) anchors.add(origin);
    return List.copyOf(anchors);
  }

  public static Correction resolve(Settlement village, Blueprint b, Pos origin) {
    Footprint before = footprint(b, origin);
    var anchors = anchors(village, origin);
    // This is a relocation trigger based on the existing local survey, not an allowed range
    // or rejection rule. Large connected layouts may extend beyond this neighborhood.
    double neighborhood = Math.max(village.radius(), DesignSurvey.CONTEXT_RADIUS);
    if (b.kind().equals("wait") || distance(before, anchors) <= neighborhood)
      return new Correction(b, "", before, before);

    Blueprint repaired = null;
    String reason = "";
    double best = Double.POSITIVE_INFINITY;
    for (int sign : new int[] {-1, 1}) {
      try {
        Blueprint candidate = shift(b, (long) sign * origin.x(), (long) sign * origin.z());
        double distance = distance(footprint(candidate, origin), anchors);
        if (distance <= neighborhood && distance < best) {
          repaired = candidate;
          best = distance;
          reason =
              sign < 0
                  ? "Recovered world coordinates supplied as local offsets"
                  : "Recovered local offsets that had been converted as world coordinates";
        }
      } catch (ArithmeticException invalidInterpretation) {
        // Another interpretation or translation can still produce a finite local shape.
      }
    }
    if (repaired == null) {
      Pos focus = DesignFocus.select(village);
      repaired = shift(b, focus.x() - before.centerX(), focus.z() - before.centerZ());
      reason = "Translated remote construction footprint to the inhabited work area";
    }
    if (repaired.kind().equals("wall")) {
      List<Pos> landmarks = new ArrayList<>(village.beds());
      landmarks.addAll(village.chests());
      landmarks.add(origin);
      Blueprint candidate = repaired;
      if (landmarks.stream().noneMatch(p -> DesignCompiler.insideWall(candidate, origin, p))) {
        Footprint area = footprint(candidate, origin);
        Pos target =
            landmarks.stream()
                .min(
                    Comparator.comparingDouble(
                        p -> Math.hypot(p.x() - area.centerX(), p.z() - area.centerZ())))
                .orElse(origin);
        repaired = shift(candidate, target.x() - area.centerX(), target.z() - area.centerZ());
        reason += "; centered the retained wall shape on its nearest village landmark";
      }
    }
    return new Correction(repaired, reason, before, footprint(repaired, origin));
  }

  public static DesignProposal apply(Settlement village, DesignProposal proposal) {
    try {
      Blueprint b = Blueprint.parse(proposal.blueprint());
      b.validateGeometry();
      Correction correction = resolve(village, b, proposal.origin());
      if (!correction.changed()) return proposal;
      return proposal.revised(
          new Gson().toJson(correction.blueprint()),
          "awaiting_validation",
          correction.reason()
              + "; coordinate_space=relative; previous_world_footprint="
              + correction.before()
              + "; local_world_footprint="
              + correction.after()
              + "; fresh terrain validation pending",
          0);
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      // Invalid geometry remains a retained proposal for normal salvage and precise diagnostics.
      return proposal;
    }
  }

  private static double distance(Footprint area, List<Pos> anchors) {
    return anchors.stream().mapToDouble(area::distance).min().orElse(Double.POSITIVE_INFINITY);
  }

  private static Blueprint shift(Blueprint b, long dx, long dz) {
    return new Blueprint(
        b.kind(),
        b.purpose(),
        Math.toIntExact(b.x() + dx),
        Math.toIntExact(b.z() + dz),
        b.width(),
        b.depth(),
        b.height(),
        b.direction(),
        b.points().stream()
            .map(p -> new Blueprint.Point(Math.toIntExact(p.x() + dx), Math.toIntExact(p.z() + dz)))
            .toList());
  }
}
