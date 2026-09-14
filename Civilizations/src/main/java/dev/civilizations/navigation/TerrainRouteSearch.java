package dev.civilizations.navigation;

import dev.civilizations.core.Pos;
import java.util.*;

/** Original bounded A* over walk/jump/descent transitions, using immutable terrain only. */
public final class TerrainRouteSearch {
  public record Edge(Pos from, Pos to) {}

  public record Step(Pos feet, List<Pos> clear, List<Pos> open) {}

  public record Result(
      List<Step> steps,
      boolean reached,
      String reason,
      int expanded,
      Map<String, Integer> rejected,
      List<Map<String, Object>> examples) {}

  private record State(Pos at, int removed) {}

  private record Node(State state, double cost, double score, Node parent, Step step) {}

  public static Pos start(NavigationMap map, Pos from) {
    for (int dy : new int[] {0, 1, -1, 2, -2}) {
      Pos p = from.add(0, dy, 0);
      if (map.passage(p, false).allowed()) return p;
    }
    return null;
  }

  public static Result search(
      NavigationMap map, Pos from, Pos target, int reach2, Set<Edge> blocked, int maxClear) {
    return search(map, from, target, reach2, blocked, maxClear, 12000);
  }

  public static Result search(
      NavigationMap map,
      Pos from,
      Pos target,
      int reach2,
      Set<Edge> blocked,
      int maxClear,
      int budget) {
    budget = Math.clamp(budget, 1000, 48000);
    Map<String, Integer> rejected = new TreeMap<>();
    List<Map<String, Object>> examples = new ArrayList<>();
    Pos start = start(map, from);
    if (start == null)
      return new Result(
          List.of(),
          false,
          "no_safe_start_cell",
          0,
          Map.of("start_obstructed_or_unsupported", 1),
          List.of(
              Map.of(
                  "position",
                  from,
                  "feet",
                  map.cell(from),
                  "head",
                  map.cell(from.add(0, 1, 0)),
                  "floor",
                  map.cell(from.add(0, -1, 0)))));
    PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
    Map<State, Double> costs = new HashMap<>();
    Node first = new Node(new State(start, 0), 0, heuristic(start, target), null, null),
        best = first;
    open.add(first);
    costs.put(first.state, 0.0);
    int expanded = 0;
    boolean external =
        Math.abs(target.x() - map.center.x()) > map.radius
            || Math.abs(target.z() - map.center.z()) > map.radius
            || Math.abs(target.y() - map.center.y()) > map.vertical;
    while (!open.isEmpty() && expanded < budget && !Thread.currentThread().isInterrupted()) {
      Node n = open.poll();
      if (n.cost > costs.getOrDefault(n.state, Double.POSITIVE_INFINITY)) continue;
      expanded++;
      if (n.state.at.distance2(target) <= reach2 && Math.abs(n.state.at.y() - target.y()) <= 3)
        return result(n, true, "reached", expanded, rejected, examples);
      if (heuristic(n.state.at, target) < heuristic(best.state.at, target)) best = n;
      for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
        for (int dy : new int[] {0, 1, -1}) {
          Pos to = n.state.at.add(d[0], dy, d[1]);
          String reason = "";
          if (!map.contains(to)) {
            rejected.merge("observation_boundary", 1, Integer::sum);
            continue;
          }
          NavigationMap.Passage passage = map.passage(to, maxClear > 0);
          if (blocked.contains(new Edge(n.state.at, to))) reason = "remembered_failed_transition";
          else if (!passage.allowed()) reason = passage.reason();
          else if (dy > 0 && map.cell(n.state.at.add(0, 2, 0)).kind() != NavigationMap.Kind.AIR)
            reason = "jump_headroom";
          else if (n.state.removed + passage.clear().size() > maxClear) reason = "clearance_budget";
          // Do not walk onto a support that this path already removed.
          if (reason.isEmpty())
            for (Node p = n; p != null && p.step != null; p = p.parent)
              if (p.step.clear.contains(to.add(0, -1, 0))) {
                reason = "removed_support";
                break;
              }
          if (!reason.isEmpty()) {
            rejected.merge(reason, 1, Integer::sum);
            String rejection = reason;
            if (examples.size() < 20
                && examples.stream().noneMatch(e -> e.get("reason").equals(rejection)))
              examples.add(
                  Map.of(
                      "from",
                      n.state.at,
                      "to",
                      to,
                      "reason",
                      reason,
                      "feet",
                      map.cell(to),
                      "head",
                      map.cell(to.add(0, 1, 0)),
                      "floor",
                      map.cell(to.add(0, -1, 0))));
            continue;
          }
          int removed = n.state.removed + passage.clear().size();
          State key = new State(to, removed);
          double cost =
              n.cost
                  + 1
                  + (dy == 0 ? 0 : 0.7)
                  + passage.clear().size() * 12
                  + passage.open().size() * 0.5;
          if (cost >= costs.getOrDefault(key, Double.POSITIVE_INFINITY)) continue;
          costs.put(key, cost);
          open.add(
              new Node(
                  key,
                  cost,
                  cost + heuristic(to, target),
                  n,
                  new Step(to, passage.clear(), passage.open())));
        }
    }
    if (external && best != first && best.state.at.distance2(start) >= 16)
      return result(best, false, "local_segment", expanded, rejected, examples);
    return new Result(
        List.of(),
        false,
        expanded >= budget ? "search_budget_exhausted" : "no_connected_route",
        expanded,
        Map.copyOf(rejected),
        List.copyOf(examples));
  }

  private static double heuristic(Pos p, Pos goal) {
    return Math.sqrt(p.distance2(goal));
  }

  private static Result result(
      Node n,
      boolean reached,
      String reason,
      int expanded,
      Map<String, Integer> rejected,
      List<Map<String, Object>> examples) {
    List<Step> steps = new ArrayList<>();
    for (Node p = n; p != null && p.step != null; p = p.parent) steps.add(p.step);
    Collections.reverse(steps);
    return new Result(
        List.copyOf(steps), reached, reason, expanded, Map.copyOf(rejected), List.copyOf(examples));
  }
}
