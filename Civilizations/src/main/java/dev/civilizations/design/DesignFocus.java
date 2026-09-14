package dev.civilizations.design;

import dev.civilizations.core.*;
import java.util.*;

/**
 * Survey the inhabited work area after merges, instead of inheriting a roof/cave anchor forever.
 */
public final class DesignFocus {
  private DesignFocus() {}

  public static Pos select(Settlement village) {
    var positions = village.snapshot().memberPositions;
    List<Pos> occupied =
        village.members().stream().map(positions::get).filter(Objects::nonNull).toList();
    return occupied.stream()
        .min(
            Comparator.comparingLong(
                p ->
                    occupied.stream()
                        .mapToLong(p::distance2)
                        .sorted()
                        .limit(Math.max(1, (occupied.size() + 1) / 2))
                        .sum()))
        .orElse(village.center());
  }

  public static boolean needsDefense(Settlement village) {
    List<Pos> landmarks = new ArrayList<>(village.beds());
    landmarks.addAll(village.chests());
    if (landmarks.isEmpty()) landmarks.add(select(village));
    return landmarks.stream()
        .anyMatch(
            p ->
                village.designs().stream()
                    .filter(d -> d.kind().equals("wall"))
                    .noneMatch(
                        d -> {
                          try {
                            return DesignCompiler.insideWall(
                                Blueprint.parse(d.blueprint()),
                                d.origin() == null ? village.center() : d.origin(),
                                p);
                          } catch (RuntimeException invalid) {
                            return false;
                          }
                        }));
  }
}
