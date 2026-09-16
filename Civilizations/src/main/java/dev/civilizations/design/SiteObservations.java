package dev.civilizations.design;

import dev.civilizations.core.*;
import dev.civilizations.world.*;
import java.util.*;
import java.util.function.Predicate;

/** Bounded, validated examples give the model actionable geometry without choosing its goal. */
public final class SiteObservations {
  private SiteObservations() {}

  public static List<Map<String, Object>> candidates(
      Terrain terrain, Settlement village, Predicate<Pos> occupied, Set<String> allowed) {
    return candidates(terrain, village, occupied, allowed, new TreeMap<>());
  }

  public static List<Map<String, Object>> candidates(
      Terrain terrain,
      Settlement village,
      Predicate<Pos> occupied,
      Set<String> allowed,
      Map<String, Integer> rejections) {
    return candidates(terrain, village, occupied, allowed, rejections, new ArrayList<>());
  }

  public static List<Map<String, Object>> candidates(
      Terrain terrain,
      Settlement village,
      Predicate<Pos> occupied,
      Set<String> allowed,
      Map<String, Integer> rejections,
      List<Map<String, Object>> failures) {
    List<Map<String, Object>> result = new ArrayList<>();
    DesignCompiler compiler = new DesignCompiler();
    if (allowed.contains("wall")) {
      List<Pos> hubs = new ArrayList<>(village.beds());
      hubs.addAll(village.chests());
      hubs.add(village.center());
      for (Pos hub :
          hubs.stream()
              .distinct()
              .sorted(
                  Comparator.<Pos>comparingInt(
                          p ->
                              village.beds().contains(p) ? 0 : village.chests().contains(p) ? 1 : 2)
                      .thenComparingLong(village.center()::distance2))
              .limit(9)
              .toList())
        for (Blueprint alternative : WallAlternatives.around(hub, village.center())) {
          if (result.stream()
                  .filter(m -> ((Blueprint) m.get("blueprint")).kind().equals("wall"))
                  .count()
              >= 2) break;
          add(result, compiler, alternative, terrain, village, occupied, rejections, failures);
        }
    }
    if (allowed.contains("path") && village.chest() != null) {
      int cx = village.chest().x() - village.center().x(),
          cz = village.chest().z() - village.center().z();
      // Stop next to storage instead of asking to pave through the chest itself.
      int endX = cx == 0 ? 0 : cx - Integer.signum(cx);
      int endZ = cx == 0 ? cz - Integer.signum(cz) : cz;
      List<Blueprint.Point> route = new ArrayList<>();
      route.add(new Blueprint.Point(0, 0));
      if (endX != 0) route.add(new Blueprint.Point(endX, 0));
      if (endZ != 0) route.add(new Blueprint.Point(endX, endZ));
      add(
          result,
          compiler,
          new Blueprint(
              "path",
              "Connect the work area to community storage",
              0,
              0,
              1,
              0,
              0,
              "north",
              List.copyOf(route)),
          terrain,
          village,
          occupied,
          rejections);
    }
    if (allowed.contains("mine"))
      for (int radius : new int[] {12, 16, 20}) {
        for (int[] d :
            new int[][] {{0, -1}, {1, 0}, {0, 1}, {-1, 0}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}}) {
          String facing = d[0] == 0 ? (d[1] < 0 ? "north" : "south") : (d[0] < 0 ? "west" : "east");
          int depth = radius == 20 ? 3 : 6;
          Blueprint proposal =
              new Blueprint(
                  "mine",
                  "Obtain mining resources for unfinished village work",
                  d[0] * radius,
                  d[1] * radius,
                  0,
                  depth,
                  0,
                  facing,
                  List.of());
          add(result, compiler, proposal, terrain, village, occupied, rejections);
        }
      }
    for (String kind : List.of("house", "farm", "lights")) {
      if (!allowed.contains(kind)) continue;
      int found = 0;
      outer:
      for (int x = -22; x <= 18; x += 2)
        for (int z = -22; z <= 18; z += 2) {
          Blueprint candidate =
              switch (kind) {
                case "house" ->
                    new Blueprint(
                        "house", "Provide missing shelter", x, z, 5, 5, 3, "north", List.of());
                case "farm" ->
                    new Blueprint(
                        "farm",
                        "Produce renewable wheat and seeds",
                        x,
                        z,
                        1,
                        1,
                        0,
                        "north",
                        List.of());
                default ->
                    new Blueprint(
                        "lights",
                        "Illuminate a safe village site",
                        0,
                        0,
                        0,
                        0,
                        0,
                        "north",
                        List.of(new Blueprint.Point(x, z)));
              };
          int before = result.size();
          add(result, compiler, candidate, terrain, village, occupied, rejections);
          found += result.size() - before;
          if (found >= 2) break outer;
        }
    }
    List<Map<String, Object>> selected =
        new ArrayList<>(
            result.stream()
                .filter(m -> ((Blueprint) m.get("blueprint")).kind().equals("mine"))
                .sorted(
                    Comparator.<Map<String, Object>>comparingLong(
                        m -> -((Number) m.get("coal_blocks_in_route")).longValue()))
                .limit(3)
                .toList());
    for (String kind : List.of("wall", "house", "farm", "lights", "path"))
      selected.addAll(
          result.stream()
              .filter(m -> ((Blueprint) m.get("blueprint")).kind().equals(kind))
              .limit(2)
              .toList());
    return List.copyOf(selected);
  }

  private static void add(
      List<Map<String, Object>> out,
      DesignCompiler compiler,
      Blueprint b,
      Terrain t,
      Settlement v,
      Predicate<Pos> occupied,
      Map<String, Integer> rejections) {
    add(out, compiler, b, t, v, occupied, rejections, new ArrayList<>());
  }

  private static void add(
      List<Map<String, Object>> out,
      DesignCompiler compiler,
      Blueprint b,
      Terrain t,
      Settlement v,
      Predicate<Pos> occupied,
      Map<String, Integer> rejections,
      List<Map<String, Object>> failures) {
    try {
      List<Pos> landmarks = new ArrayList<>(v.beds());
      landmarks.addAll(v.chests());
      var compiled = compiler.compile(b, t, v.center(), "candidate", occupied, landmarks);
      long coal =
          compiled.jobs().stream()
              .filter(j -> Set.of("COAL_ORE", "DEEPSLATE_COAL_ORE").contains(j.expected))
              .count();
      out.add(
          Map.of(
              "blueprint",
              b,
              "coal_blocks_in_route",
              coal,
              "actions",
              compiled.jobs().size(),
              "preparation_actions",
              compiled.jobs().stream()
                  .filter(j -> j.kind == Job.Kind.CLEAR || j.kind == Job.Kind.MINE)
                  .count(),
              "materials",
              compiled.materials()));
    } catch (IllegalArgumentException invalid) {
      String reason = b.kind() + ": " + invalid.getMessage().replaceAll("-?\\d+", "#");
      rejections.merge(reason, 1, Integer::sum);
      if (failures.size() < 12
          && failures.stream().noneMatch(f -> f.get("category").equals(reason)))
        failures.add(
            Map.of(
                "category",
                reason,
                "blueprint",
                b,
                "reason",
                invalid.getMessage(),
                "coordinate_space",
                "relative",
                "world_origin",
                v.center()));
    }
  }
}
