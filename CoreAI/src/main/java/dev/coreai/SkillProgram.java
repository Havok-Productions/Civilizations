package dev.coreai;

import com.google.gson.*;
import java.util.*;

/** Model-authored, finite host-action program. Coordinates are relative to the captured origin. */
public record SkillProgram(String explanation, List<Step> steps) {
  public enum Op {
    WALK,
    CLEAR,
    PLACE_SUPPORT,
    CLASSIFY,
    SEARCH,
    TUNE,
    VERIFY
  }

  public record Step(Op op, int x, int y, int z, String material) {}

  public SkillProgram {
    steps = List.copyOf(steps);
  }

  public static final Set<String> SUPPORTS =
      Set.of("DIRT", "COBBLESTONE", "OAK_PLANKS", "BIRCH_PLANKS", "SPRUCE_PLANKS");

  public static SkillProgram parse(String text) {
    if (text == null || text.length() > 16000)
      throw new IllegalArgumentException("Program size limit");
    JsonObject json = JsonParser.parseString(text).getAsJsonObject();
    if (!json.keySet().equals(Set.of("explanation", "steps")))
      throw new IllegalArgumentException("Unknown program fields");
    String explanation = json.get("explanation").getAsString();
    if (explanation.length() > 1000) throw new IllegalArgumentException("Explanation size");
    List<Step> steps = new ArrayList<>();
    JsonArray values = json.getAsJsonArray("steps");
    if (values.isEmpty())
      throw new IllegalArgumentException("At least one instruction is required");
    for (JsonElement entry : values) {
      JsonObject value = entry.getAsJsonObject();
      if (!value.keySet().equals(Set.of("op", "x", "y", "z", "material")))
        throw new IllegalArgumentException("Unknown instruction fields");
      Op op = Op.valueOf(value.get("op").getAsString());
      int x = integer(value, "x"), y = integer(value, "y"), z = integer(value, "z");
      String material = value.get("material").getAsString();
      if (op == Op.TUNE) {
        ParameterCatalog.validate(material, x);
        if (y != 0 || z != 0)
          throw new IllegalArgumentException("TUNE uses x=value,y=z=0,material=parameter key");
      } else if (op == Op.SEARCH) {
        if (y != 0 || z != 0 || !material.isEmpty())
          throw new IllegalArgumentException("SEARCH uses x=radius and y=z=0, empty material");
      } else if (op == Op.CLASSIFY) {
        if (!Set.of("PASSABLE", "CLEARABLE", "OBSTACLE").contains(material))
          throw new IllegalArgumentException("CLASSIFY requires an environment category");
      } else if (op == Op.PLACE_SUPPORT) {
        if (!SUPPORTS.contains(material))
          throw new IllegalArgumentException("Unsupported building material");
      } else if (!material.isEmpty())
        throw new IllegalArgumentException("Only placement specifies material");
      if (op == Op.VERIFY && (x != 0 || y != 0 || z != 0 || steps.size() != values.size() - 1))
        throw new IllegalArgumentException("VERIFY must be last and uses the host goal");
      steps.add(new Step(op, x, y, z, material));
    }
    // Verification belongs to the host. A missing terminator need not discard usable actions;
    // explicit VERIFY instructions still must be last and cannot change the original goal.
    if (steps.getLast().op() != Op.VERIFY) steps.add(new Step(Op.VERIFY, 0, 0, 0, ""));
    return new SkillProgram(explanation, steps);
  }

  /** Op-specific grammar prevents a model assigning classification labels to CLEAR or WALK. */
  public static String schema() {
    List<Map<String, Object>> choices = new ArrayList<>();
    for (Op op : Op.values()) {
      if (op == Op.TUNE) {
        ParameterCatalog.SPECS.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(
                entry ->
                    choices.add(
                        Map.of(
                            "type",
                            "object",
                            "additionalProperties",
                            false,
                            "required",
                            List.of("op", "x", "y", "z", "material"),
                            "properties",
                            Map.of(
                                "op",
                                Map.of("const", "TUNE"),
                                "x",
                                Map.of("type", "integer"),
                                "y",
                                Map.of("const", 0),
                                "z",
                                Map.of("const", 0),
                                "material",
                                Map.of("const", entry.getKey())))));
        continue;
      }
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("op", Map.of("const", op.name()));
      fields.put("x", op == Op.VERIFY ? Map.of("const", 0) : Map.of("type", "integer"));
      fields.put(
          "y", op == Op.VERIFY || op == Op.SEARCH ? Map.of("const", 0) : Map.of("type", "integer"));
      fields.put(
          "z", op == Op.VERIFY || op == Op.SEARCH ? Map.of("const", 0) : Map.of("type", "integer"));
      fields.put(
          "material",
          op == Op.PLACE_SUPPORT
              ? Map.of("enum", SUPPORTS.stream().sorted().toList())
              : op == Op.CLASSIFY
                  ? Map.of("enum", List.of("PASSABLE", "CLEARABLE", "OBSTACLE"))
                  : Map.of("const", ""));
      choices.add(
          Map.of(
              "type",
              "object",
              "additionalProperties",
              false,
              "required",
              List.of("op", "x", "y", "z", "material"),
              "properties",
              fields));
    }
    return new Gson()
        .toJson(
            Map.of(
                "type",
                "object",
                "additionalProperties",
                false,
                "required",
                List.of("explanation", "steps"),
                "properties",
                Map.of(
                    "explanation",
                    Map.of("type", "string", "maxLength", 1000),
                    "steps",
                    Map.of(
                        "type",
                        "array",
                        "minItems",
                        2,
                        "description",
                        "Finite instructions with final VERIFY",
                        "items",
                        Map.of("oneOf", choices)))));
  }

  private static int integer(JsonObject value, String key) {
    try {
      return value.get(key).getAsBigDecimal().intValueExact();
    } catch (Exception e) {
      throw new IllegalArgumentException("Integer coordinate required");
    }
  }

  public String source() {
    return new Gson().toJson(this);
  }
}
