package test;

import dev.civilizations.core.Pos;
import dev.civilizations.learning.*;
import dev.civilizations.lib.gson.*;
import dev.civilizations.navigation.NavigationMap;
import dev.coreai.SkillProgram;
import java.nio.file.*;
import java.util.*;

public class RecoveryModelCases {
  public static void main(String[] args) throws Exception {
    Pos at = new Pos(0, 64, 0);
    List<Map<String, Object>> cases = new ArrayList<>();
    for (String name : List.of("unknown_elevated_goal", "loose_dirt", "protected_wall")) {
      Map<Pos, NavigationMap.Cell> cells = new HashMap<>();
      for (int x = -8; x <= 8; x++)
        for (int z = -8; z <= 8; z++)
          for (int y = -2; y <= 30; y++)
            cells.put(
                at.add(x, y, z),
                new NavigationMap.Cell(
                    y < 0 ? "STONE" : "AIR",
                    y < 0 ? NavigationMap.Kind.SOLID : NavigationMap.Kind.AIR,
                    "",
                    y < 0
                        ? "requires_mining_task_and_suitable_tool_not_CLEAR"
                        : "no_clearance_needed"));
      Pos goal = at.add(name.equals("loose_dirt") ? 2 : 6, 0, 0);
      String failure = "native_route_blocked_by_solid_wall";
      if (name.equals("unknown_elevated_goal")) {
        goal = at.add(-11, 27, -8);
        failure =
            "goal_cell_unobserved; no_connected_route; a distant classification previously tried"
                + " walking before observing";
      }
      if (name.equals("loose_dirt")) {
        cells.put(
            goal,
            new NavigationMap.Cell(
                "DIRT",
                NavigationMap.Kind.SOFT,
                "minecraft:dirt",
                "CLEAR_within_reach_live_checks_and_retained_drops"));
        failure = "site_needs_natural_clearance";
      }
      if (name.equals("protected_wall"))
        for (int z = -2; z <= 2; z++)
          for (int y = 0; y <= 2; y++)
            cells.put(
                at.add(2, y, z),
                new NavigationMap.Cell(
                    "STONE", NavigationMap.Kind.SOLID, "minecraft:stone", "protected_or_reserved"));
      var context =
          SkillContext.create(new NavigationMap(at, 20, 30, cells), at, goal, 1, Map.of(), failure);
      var report = new LinkedHashMap<String, Object>(context.observation());
      report.put(
          "verification_goal",
          name.equals("loose_dirt")
              ? "CLEAR_SITE: original goal block must become AIR"
              : "ARRIVAL: reach original goal with normal movement");
      cases.add(Map.of("name", name, "report", report));
    }
    Files.writeString(
        Path.of(args[0]),
        new Gson()
            .toJson(
                Map.of(
                    "system",
                    RecoveryExperiments.SYSTEM,
                    "schema",
                    JsonParser.parseString(SkillProgram.schema()),
                    "cases",
                    cases)));
  }
}
