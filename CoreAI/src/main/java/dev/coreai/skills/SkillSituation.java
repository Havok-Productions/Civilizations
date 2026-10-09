package dev.coreai.skills;

import com.google.gson.Gson;
import dev.coreai.SkillProgram;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Translation-only physical preconditions supplied by the host, without world coordinates. */
public final class SkillSituation {
  public record Point(int x, int y, int z) {}

  public record Cell(
      Point relative,
      String material,
      String state,
      String kind,
      String removal,
      boolean observed) {
    public Cell {
      Objects.requireNonNull(relative);
      Objects.requireNonNull(material);
      Objects.requireNonNull(state);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(removal);
    }
  }

  /** The complete relative terrain is hashed; large voxel snapshots are not duplicated on disk. */
  public record Descriptor(
      String scope,
      Point goal,
      int reachSquared,
      String terrainSha256,
      int observedCells,
      Map<String, Integer> inventory) {
    public Descriptor {
      Objects.requireNonNull(scope);
      Objects.requireNonNull(goal);
      if (reachSquared < 0
          || observedCells < 0
          || terrainSha256 == null
          || !terrainSha256.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("Invalid portable skill descriptor");
      var ordered = new TreeMap<String, Integer>();
      for (var entry : Objects.requireNonNull(inventory).entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0)
          throw new IllegalArgumentException("Invalid inventory precondition");
        if (entry.getValue() > 0) ordered.put(entry.getKey(), entry.getValue());
      }
      inventory = Collections.unmodifiableMap(ordered);
    }
  }

  private final Descriptor descriptor;
  private final Map<Point, Cell> cells;

  public SkillSituation(
      String scope,
      Point goal,
      int reachSquared,
      List<Cell> terrain,
      Map<String, Integer> inventory) {
    var ordered = new ArrayList<>(Objects.requireNonNull(terrain));
    ordered.sort(
        Comparator.comparingInt((Cell c) -> c.relative().x())
            .thenComparingInt(c -> c.relative().y())
            .thenComparingInt(c -> c.relative().z()));
    var indexed = new HashMap<Point, Cell>();
    int observed = 0;
    for (Cell cell : ordered) {
      if (indexed.putIfAbsent(cell.relative(), cell) != null)
        throw new IllegalArgumentException("Duplicate relative terrain cell");
      if (isObserved(cell)) observed++;
    }
    cells = Collections.unmodifiableMap(indexed);
    descriptor =
        new Descriptor(
            scope, goal, reachSquared, digest(new Gson().toJson(ordered)), observed, inventory);
  }

  public Descriptor descriptor() {
    return descriptor;
  }

  /** Missing or explicitly unobserved targets must be surveyed before a remembered plan is used. */
  public boolean observedFor(SkillProgram program) {
    if (!observed(new Point(0, 0, 0)) || !observed(descriptor.goal())) return false;
    for (var step : program.steps()) {
      switch (step.op().name()) {
        // Controller tuning and a growing search invalidate this fixed captured footprint.
        case "TUNE", "SEARCH" -> {
          return false;
        }
        case "VERIFY", "ACQUIRE" -> {}
        default -> {
          if (!observed(new Point(step.x(), step.y(), step.z()))) return false;
        }
      }
    }
    return true;
  }

  private boolean observed(Point point) {
    return isObserved(cells.get(point));
  }

  private static boolean isObserved(Cell cell) {
    return cell != null
        && cell.observed()
        && !cell.kind().equals("UNKNOWN")
        && !cell.material().equals("UNKNOWN");
  }

  static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
