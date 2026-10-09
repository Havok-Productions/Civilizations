package dev.civilizations.learning;

import com.google.gson.Gson;
import dev.civilizations.core.Pos;
import dev.civilizations.navigation.*;
import dev.coreai.SkillProgram;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Candidate generation on immutable voxels. Predictions are never physical success labels. */
public final class VoxelRecoveryPlanner {
  public record Candidate(
      String strategy, SkillProgram program, double cost, List<Double> features, String group) {}

  public record Evaluation(List<Candidate> candidates, Map<String, String> failures) {}

  private VoxelRecoveryPlanner() {}

  public static Evaluation evaluate(SkillContext context) {
    List<Candidate> candidates = new ArrayList<>();
    Map<String, String> failures = new LinkedHashMap<>();
    // Site clearance has a different success condition; do not confuse arrival with clearance.
    if (Objects.toString(context.observation().get("verification_goal"), "")
        .startsWith("CLEAR_SITE")) {
      failures.put("scope", "site_goal_requires_clearance_not_arrival");
      return new Evaluation(List.of(), Map.copyOf(failures));
    }
    String group = group(context);
    add(context, context.map(), "WALK", Map.of(), group, candidates, failures);
    add(context, context.map(), "CLEAR", Map.of(), group, candidates, failures);
    var mining = new HashMap<>(context.map().cells());
    mining.replaceAll(
        (p, c) ->
            c.kind() == NavigationMap.Kind.SOLID
                    && c.removal().equals("requires_mining_task_and_suitable_tool_not_CLEAR")
                    && minePrerequisitesObserved(context.map(), p)
                ? new NavigationMap.Cell(
                    c.material(), NavigationMap.Kind.SOFT, c.state(), c.removal())
                : c);
    add(context, changed(context.map(), mining), "MINE", Map.of(), group, candidates, failures);
    // One connected support at a time. These baseline candidates do not limit teacher proposals.
    String material =
        context.inventory().entrySet().stream()
            .filter(e -> e.getValue() > 0 && SkillProgram.SUPPORTS.contains(e.getKey()))
            .map(Map.Entry::getKey)
            .sorted()
            .findFirst()
            .orElse(null);
    if (material != null) {
      List<Pos> supports =
          context.map().cells().entrySet().stream()
              .filter(
                  e ->
                      e.getValue().kind() == NavigationMap.Kind.AIR
                          && !e.getValue().removal().equals("protected_or_reserved"))
              .map(Map.Entry::getKey)
              .filter(p -> adjacentSupport(context.map(), p))
              .filter(
                  p ->
                      context.map().cell(p.add(0, 1, 0)).kind() == NavigationMap.Kind.AIR
                          && context.map().cell(p.add(0, 2, 0)).kind() == NavigationMap.Kind.AIR)
              .sorted(
                  Comparator.comparingLong((Pos p) -> p.add(0, 1, 0).distance2(context.goal()))
                      .thenComparing(Pos::key))
              .limit(12)
              .toList();
      for (Pos support : supports) {
        var cells = new HashMap<>(context.map().cells());
        cells.put(
            support,
            new NavigationMap.Cell(material, NavigationMap.Kind.SOLID, "", "planned_support"));
        add(
            context,
            changed(context.map(), cells),
            "SUPPORT",
            Map.of(support, material),
            group,
            candidates,
            failures);
      }
    } else failures.put("SUPPORT", "no_support_material_in_inventory");
    // Different strategies may produce identical actions; only execute/label the unique program.
    var unique = new LinkedHashMap<String, Candidate>();
    candidates.stream()
        .sorted(Comparator.comparingDouble(Candidate::cost))
        .forEach(c -> unique.putIfAbsent(new Gson().toJson(c.program().steps()), c));
    return new Evaluation(List.copyOf(unique.values()), Map.copyOf(failures));
  }

  private static boolean adjacentSupport(NavigationMap map, Pos p) {
    for (Pos d :
        List.of(
            p.add(1, 0, 0), p.add(-1, 0, 0), p.add(0, 0, 1), p.add(0, 0, -1), p.add(0, -1, 0))) {
      var k = map.cell(d).kind();
      if (k == NavigationMap.Kind.SOLID || k == NavigationMap.Kind.SOFT) return true;
    }
    return false;
  }

  /** Mirror the executor's measurable prerequisites before suggesting excavation. */
  private static boolean minePrerequisitesObserved(NavigationMap map, Pos p) {
    String material = map.cell(p).material();
    if (!dev.civilizations.core.HarvestCatalog.mineral(material)
        && !Set.of("DIRT", "GRASS_BLOCK").contains(material)) return false;
    if (Set.of("SAND", "RED_SAND", "GRAVEL").contains(map.cell(p.add(0, 1, 0)).material()))
      return false;
    for (int x = -1; x <= 1; x++)
      for (int y = -1; y <= 1; y++)
        for (int z = -1; z <= 1; z++) {
          var cell = map.cell(p.add(x, y, z));
          if (cell.kind() == NavigationMap.Kind.UNKNOWN
              || cell.kind() == NavigationMap.Kind.FLUID
              || cell.state().contains("waterlogged=true")) return false;
        }
    return true;
  }

  private static NavigationMap changed(NavigationMap map, Map<Pos, NavigationMap.Cell> cells) {
    return new NavigationMap(map.center, map.radius, map.vertical, cells);
  }

  private static void add(
      SkillContext context,
      NavigationMap simulation,
      String strategy,
      Map<Pos, String> supports,
      String group,
      List<Candidate> candidates,
      Map<String, String> failures) {
    var route =
        TerrainRouteSearch.search(
            simulation,
            context.origin(),
            context.goal(),
            context.reach2(),
            Set.of(),
            strategy.equals("WALK") ? 0 : 8);
    boolean segment =
        !route.reached() && route.reason().equals("local_segment") && !route.steps().isEmpty();
    if (!route.reached() && !segment) {
      failures.put(strategy, route.reason());
      return;
    }
    List<SkillProgram.Step> steps = new ArrayList<>();
    Set<Pos> edited = new HashSet<>();
    int clear = 0, mine = 0, place = 0;
    for (var step : route.steps()) {
      Pos below = step.feet().add(0, -1, 0);
      if (supports.containsKey(below) && edited.add(below)) {
        steps.add(
            relative(SkillProgram.Op.PLACE_SUPPORT, below, context.origin(), supports.get(below)));
        place++;
      }
      for (Pos p : step.clear())
        if (edited.add(p)) {
          var cell = context.map().cell(p);
          boolean needsMining = cell.kind() == NavigationMap.Kind.SOLID;
          steps.add(
              relative(
                  needsMining ? SkillProgram.Op.MINE : SkillProgram.Op.CLEAR,
                  p,
                  context.origin(),
                  needsMining ? cell.material() : ""));
          if (needsMining) mine++;
          else clear++;
        }
      steps.add(relative(SkillProgram.Op.WALK, step.feet(), context.origin(), ""));
    }
    if (steps.isEmpty()) {
      failures.put(strategy, "already_at_goal_no_recovery_needed");
      return;
    }
    if (segment)
      steps.add(
          relative(SkillProgram.Op.OBSERVE, route.steps().getLast().feet(), context.origin(), ""));
    steps.add(new SkillProgram.Step(SkillProgram.Op.VERIFY, 0, 0, 0, ""));
    var program =
        new SkillProgram(
            (segment
                    ? "Voxel simulation predicts a local segment; OBSERVE refreshes the map and"
                          + " retains the distant original goal. Strategy "
                    : "Voxel simulation predicts a connected route using ")
                + strategy
                + ". Live executors must recheck blocks, reach, tools, items and original arrival.",
            steps);
    // The same grammar and executor admission apply as for a teacher-authored program.
    try {
      program = SkillProgram.parse(new Gson().toJson(program));
    } catch (RuntimeException invalid) {
      failures.put(strategy, "program_not_admissible: " + invalid.getMessage());
      return;
    }
    double cost = route.steps().size() + clear * 4 + mine * 8 + place * 6;
    List<Double> features =
        List.of(
            Math.min(4, route.steps().size() / 20.0),
            clear / 8.0,
            mine / 8.0,
            place / 4.0,
            context.inventory().keySet().stream().anyMatch(k -> k.endsWith("_PICKAXE")) ? 1.0 : 0.0,
            Math.abs(context.goal().y() - context.origin().y()) / 10.0,
            context.map().passage(context.goal(), false).allowed() ? 1.0 : 0.0,
            context.inventory().values().stream().mapToInt(Integer::intValue).sum() / 64.0);
    candidates.add(new Candidate(strategy, program, cost, features, group));
  }

  private static SkillProgram.Step relative(
      SkillProgram.Op op, Pos p, Pos origin, String material) {
    return new SkillProgram.Step(
        op, p.x() - origin.x(), p.y() - origin.y(), p.z() - origin.z(), material);
  }

  public static String group(SkillContext context) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      context.map().cells().entrySet().stream()
          .filter(e -> e.getValue().kind() != NavigationMap.Kind.UNKNOWN)
          .sorted(
              Comparator.<Map.Entry<Pos, NavigationMap.Cell>>comparingInt(e -> e.getKey().x())
                  .thenComparingInt(e -> e.getKey().y())
                  .thenComparingInt(e -> e.getKey().z()))
          .forEach(
              e -> {
                Pos p = e.getKey(), o = context.origin();
                String row =
                    (p.x() - o.x())
                        + ","
                        + (p.y() - o.y())
                        + ","
                        + (p.z() - o.z())
                        + ":"
                        + e.getValue()
                        + "\n";
                digest.update(row.getBytes(StandardCharsets.UTF_8));
              });
      // Group all actions/inventories/goals on the same terrain together, including translations.
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
