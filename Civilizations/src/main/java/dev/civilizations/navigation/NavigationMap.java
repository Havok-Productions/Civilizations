package dev.civilizations.navigation;

import dev.civilizations.core.Pos;
import java.util.*;

/** Immutable local voxel map. Unknown cells are never treated as empty space. */
public final class NavigationMap {
  public enum Kind {
    AIR,
    SOLID,
    SOFT,
    OPENABLE,
    HAZARD,
    FLUID,
    OBSTACLE,
    CLEARABLE,
    UNCLASSIFIED,
    UNKNOWN
  }

  public record Cell(String material, Kind kind) {}

  public record Passage(boolean allowed, String reason, List<Pos> clear, List<Pos> open) {}

  public final Pos center;
  public final int radius, vertical;
  private final Map<Pos, Cell> cells;
  public final String fingerprint;

  public NavigationMap(Pos center, int radius, int vertical, Map<Pos, Cell> cells) {
    this.center = center;
    this.radius = radius;
    this.vertical = vertical;
    // Dense coordinate hashes cluster badly in MapN's linear probing during Map.copyOf.
    // HashMap keeps bucket collision handling while the wrapper preserves snapshot immutability.
    this.cells = Collections.unmodifiableMap(new HashMap<>(cells));
    // Stable geometry fingerprint, independent of insertion order and map acquisition timing.
    long hash = 0;
    for (var e : cells.entrySet())
      hash +=
          (long) e.getKey().hashCode() * 0x9e3779b9L
              ^ (e.getValue().material() + ":" + e.getValue().kind().name()).hashCode();
    fingerprint = Long.toUnsignedString(hash, 16);
  }

  public Cell cell(Pos p) {
    return cells.getOrDefault(p, new Cell("UNKNOWN", Kind.UNKNOWN));
  }

  public List<Pos> positions(String material) {
    return cells.entrySet().stream()
        .filter(e -> e.getValue().material().equals(material))
        .map(Map.Entry::getKey)
        .toList();
  }

  public List<Pos> investigationCandidates(Pos origin) {
    return cells.entrySet().stream()
        .filter(
            e ->
                e.getKey().distance2(origin) <= 400
                    && (e.getValue().kind() == Kind.UNCLASSIFIED
                        || e.getValue().kind() == Kind.OBSTACLE
                        || e.getValue().kind() == Kind.AIR
                            && !Set.of("AIR", "CAVE_AIR", "VOID_AIR")
                                .contains(e.getValue().material())))
        .map(Map.Entry::getKey)
        .sorted(
            Comparator.<Pos>comparingInt(
                    p ->
                        cell(p).kind() == Kind.UNCLASSIFIED
                            ? 0
                            : cell(p).kind() == Kind.OBSTACLE ? 1 : 2)
                .thenComparingLong(origin::distance2))
        .limit(96)
        .toList();
  }

  public boolean contains(Pos p) {
    return Math.abs((long) p.x() - center.x()) <= radius
        && Math.abs((long) p.z() - center.z()) <= radius
        && Math.abs((long) p.y() - center.y()) <= vertical;
  }

  public Passage passage(Pos feet, boolean salvage) {
    Cell ground = cell(feet.add(0, -1, 0));
    if (ground.kind != Kind.SOLID && ground.kind != Kind.SOFT)
      return new Passage(
          false,
          switch (ground.kind) {
            case UNKNOWN -> "unknown_support";
            case UNCLASSIFIED -> "unclassified_support";
            case FLUID -> "fluid_floor";
            case HAZARD -> "damaging_or_explosive_floor";
            default -> "unsupported_floor";
          },
          List.of(),
          List.of());
    List<Pos> clear = new ArrayList<>(), open = new ArrayList<>();
    for (Pos p : List.of(feet, feet.add(0, 1, 0))) {
      Cell c = cell(p);
      switch (c.kind) {
        case AIR -> {}
        case SOFT, CLEARABLE -> {
          if (!salvage) return new Passage(false, "natural_obstacle", List.of(), List.of());
          clear.add(p);
        }
        case OPENABLE -> open.add(p);
        default -> {
          return new Passage(
              false,
              c.kind == Kind.UNKNOWN
                  ? "unknown_region_or_height"
                  : c.kind == Kind.UNCLASSIFIED
                      ? "unclassified_block_needs_observation"
                      : c.kind == Kind.FLUID
                          ? "fluid_requires_special_movement"
                          : c.kind == Kind.HAZARD
                              ? "damaging_or_explosive_block"
                              : "solid_or_protected_obstacle",
              List.of(),
              List.of());
        }
      }
    }
    return new Passage(true, "", List.copyOf(clear), List.copyOf(open));
  }

  /** Surface water is an escape state, never an invitation for dry routes to enter water. */
  public boolean surfaceWater(Pos feet) {
    Cell body = cell(feet), head = cell(feet.add(0, 1, 0)), floor = cell(feet.add(0, -1, 0));
    return (body.material.equals("WATER")
            || body.kind == Kind.AIR && floor.material.equals("WATER"))
        && head.kind == Kind.AIR;
  }

  /** Compact complete volume, palette + runs, x then z then y. Includes floor/head margins. */
  public Map<String, Object> describe() {
    List<String> palette = new ArrayList<>();
    Map<String, Integer> index = new HashMap<>();
    List<Integer> runs = new ArrayList<>();
    int last = -1, count = 0;
    for (int x = center.x() - radius; x <= center.x() + radius; x++)
      for (int z = center.z() - radius; z <= center.z() + radius; z++)
        for (int y = center.y() - vertical - 1; y <= center.y() + vertical + 2; y++) {
          Cell c = cell(new Pos(x, y, z));
          String key = c.material + ":" + c.kind;
          Integer id = index.get(key);
          if (id == null) {
            id = palette.size();
            index.put(key, id);
            palette.add(key);
          }
          if (id == last) count++;
          else {
            if (count > 0) {
              runs.add(last);
              runs.add(count);
            }
            last = id;
            count = 1;
          }
        }
    if (count > 0) {
      runs.add(last);
      runs.add(count);
    }
    return Map.of(
        "center",
        center,
        "radius",
        radius,
        "vertical",
        vertical,
        "order",
        "x,z,y; y from center-vertical-1 to center+vertical+2 inclusive",
        "palette",
        palette,
        "runs",
        runs,
        "fingerprint",
        fingerprint);
  }
}
