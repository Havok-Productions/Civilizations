package dev.civilizations.design;

import com.google.gson.*;
import java.util.*;

/** Ground an urgent supply choice in surveyed geometry instead of regenerating its coordinates. */
public final class SupplyDesignSchema {
  private SupplyDesignSchema() {}

  public static String coalRoutes(List<Map<String, Object>> examples) {
    return surveyed(
        examples.stream()
            .filter(
                candidate ->
                    ((Blueprint) candidate.get("blueprint")).kind().equals("mine")
                        && ((Number) candidate.get("coal_blocks_in_route")).longValue() > 0)
            .toList());
  }

  public static String surveyed(List<Map<String, Object>> examples) {
    JsonArray options = new JsonArray();
    for (Map<String, Object> candidate : examples) {
      Blueprint b = (Blueprint) candidate.get("blueprint");
      JsonObject schema = JsonParser.parseString(Blueprint.SCHEMA).getAsJsonObject();
      JsonObject properties = schema.getAsJsonObject("properties");
      JsonObject blueprint = new Gson().toJsonTree(b).getAsJsonObject();
      for (String field :
          List.of("kind", "x", "z", "width", "depth", "height", "direction", "points")) {
        JsonObject value = new JsonObject();
        value.add("const", blueprint.get(field));
        properties.add(field, value);
      }
      options.add(schema);
    }
    if (options.isEmpty()) return Blueprint.SCHEMA;
    if (options.size() == 1) return options.get(0).toString();
    JsonObject result = new JsonObject();
    result.add("anyOf", options);
    return result.toString();
  }
}
