package dev.civilizations.learning;

import dev.civilizations.core.Pos;
import dev.civilizations.navigation.NavigationMap;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/**
 * Compact relative terrain, available materials and a stable local context for remembered skills.
 */
public record SkillContext(
    String key,
    NavigationMap map,
    Pos origin,
    Pos goal,
    int reach2,
    Map<String, Integer> inventory,
    String failure,
    Map<String, Object> observation) {
  /**
   * Verified behavior may survive different error wording, but not changed physical conditions.
   * Full map fingerprint includes block state; goal type, plugin version and tuned rules stay
   * scoped.
   */
  public String reuseKey() {
    var state = new TreeMap<String, Object>();
    state.put("map", map.fingerprint);
    state.put("origin", origin);
    state.put("goal", goal);
    state.put("reach", reach2);
    state.put("radius", map.radius);
    state.put("vertical", map.vertical);
    state.put("inventory", new TreeMap<>(inventory));
    int suffix = key.indexOf(':');
    state.put("scope", suffix < 0 ? "" : key.substring(suffix));
    for (String field : List.of("physical_block_probes", "tunable_parameters", "known_rule_count"))
      if (observation.containsKey(field)) state.put(field, observation.get(field));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      new com.google.gson.Gson().toJson(state).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  public static SkillContext create(
      NavigationMap map,
      Pos origin,
      Pos goal,
      int reach2,
      Map<String, Integer> inventory,
      String failure) {
    List<Map<String, Object>> levels = new ArrayList<>();
    StringBuilder signature = new StringBuilder();
    for (int dy = -2; dy <= 3; dy++) {
      List<String> rows = new ArrayList<>();
      for (int z = -4; z <= 4; z++) {
        StringBuilder row = new StringBuilder();
        for (int x = -4; x <= 4; x++) {
          var c = map.cell(origin.add(x, dy, z));
          char symbol = RecoveryTerrainView.symbol(c);
          row.append(symbol);
          signature
              .append(c.material())
              .append(':')
              .append(symbol)
              .append(':')
              .append(c.state())
              .append(';');
        }
        rows.add(row.toString());
      }
      levels.add(Map.of("relative_y", dy, "rows_z_minus4_to_plus4", rows));
    }
    List<Pos> walk = new ArrayList<>(), clear = new ArrayList<>(), place = new ArrayList<>();
    for (int x = -8; x <= 8; x++)
      for (int z = -8; z <= 8; z++)
        for (int y = -3; y <= 3; y++) {
          Pos p = origin.add(x, y, z);
          if (map.passage(p, false).allowed()) walk.add(new Pos(x, y, z));
          if (map.cell(p).kind() == NavigationMap.Kind.SOFT && origin.distance2(p) <= 36)
            clear.add(new Pos(x, y, z));
          if (map.cell(p).kind() == NavigationMap.Kind.AIR
              && map.cell(p.add(0, 1, 0)).kind() == NavigationMap.Kind.AIR
              && map.cell(p.add(0, 2, 0)).kind() == NavigationMap.Kind.AIR
              && origin.distance2(p) <= 25) place.add(new Pos(x, y, z));
        }
    java.util.Comparator<Pos> near =
        java.util.Comparator.comparingLong(p -> p.distance2(new Pos(0, 0, 0)));
    walk.sort(near);
    clear.sort(near);
    place.sort(near);
    Pos delta = new Pos(goal.x() - origin.x(), goal.y() - origin.y(), goal.z() - origin.z());
    signature.append(delta).append(':').append(reach2).append(':').append(failure);
    signature.append(new TreeMap<>(inventory)).append(map.radius);
    signature.append(map.fingerprint).append(origin).append(map.vertical);
    String key;
    try {
      key =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(signature.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("failure", failure);
    report.put("search_radius", map.radius);
    report.put("goal_relative", delta);
    report.put("inventory", Map.copyOf(inventory));
    report.put("reach_squared", reach2);
    report.put(
        "legend",
        "Rows: x -4..+4; z -4..+4. .=passable, #=ordinary solid requiring mining task/tool,"
            + " P=protected/reserved, B=structure/support, s=natural CLEAR candidate, D=openable,"
            + " !=damaging/explosive, ~=fluid (not a walk route), X=obstacle, c=clearable,"
            + " u=unclassified; ?=unobserved. Coordinates relative to current feet origin.");
    report.put("levels", levels);
    report.put("level_x_coordinates", List.of(-4, -3, -2, -1, 0, 1, 2, 3, 4));
    report.put("nearby_obstacle_examples", RecoveryTerrainView.nearbyObstacles(map, origin));
    report.put("route_height", RecoveryTerrainView.describe(map, origin, goal));
    report.put("goal_spatial_facts", RecoverySpatialFacts.at(map, origin, goal));
    report.put("origin_spatial_facts", RecoverySpatialFacts.at(map, origin, origin));
    report.put("nearby_stands", walk.stream().limit(16).toList());
    report.put("natural_clear_candidates", clear.stream().limit(24).toList());
    // The nearest obstacle list may be filled by ordinary walls, hiding the natural obstruction.
    // Preserve its observed material and state alongside the same bounded candidate coordinates.
    report.put(
        "natural_clear_candidates_details",
        clear.stream()
            .limit(24)
            .map(
                relative -> {
                  var cell = map.cell(origin.add(relative.x(), relative.y(), relative.z()));
                  return Map.<String, Object>of(
                      "relative", relative,
                      "material", cell.material(),
                      "block_state", cell.state(),
                      "map_category", cell.kind(),
                      "removal", cell.removal(),
                      "live_checks_required", true);
                })
            .toList());
    report.put(
        "air_support_candidates_requiring_live_checks",
        inventory.entrySet().stream()
                .anyMatch(
                    e -> e.getValue() > 0 && dev.coreai.SkillProgram.SUPPORTS.contains(e.getKey()))
            ? place.stream().limit(24).toList()
            : List.of());
    return new SkillContext(
        key, map, origin, goal, reach2, Map.copyOf(inventory), failure, Map.copyOf(report));
  }
}
