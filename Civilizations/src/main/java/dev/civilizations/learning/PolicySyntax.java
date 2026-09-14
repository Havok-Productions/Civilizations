package dev.civilizations.learning;

import com.google.gson.*;
import dev.coreai.PolicyProgram;

/** A typed model-facing syntax tree removes string-code syntax guessing. */
public final class PolicySyntax {
  private PolicySyntax() {}

  public static final String SCHEMA = schema();

  private static String schema() {
    JsonObject root = new JsonObject(), defs = new JsonObject();
    String leaf =
        "{\"anyOf\":[{\"type\":\"number\",\"minimum\":-1000000,\"maximum\":1000000},{\"type\":\"string\",\"enum\":[\"base\",\"failures\",\"distance\",\"missing\",\"repair\",\"continuing\",\"food\",\"danger\",\"vertical\",\"detour\"]}]}";
    defs.add("expr0", JsonParser.parseString(leaf));
    for (int depth = 1; depth <= 3; depth++) {
      JsonArray choices = JsonParser.parseString(leaf).getAsJsonObject().getAsJsonArray("anyOf");
      for (boolean conditional : new boolean[] {false, true}) {
        JsonObject node = new JsonObject(), properties = new JsonObject();
        node.addProperty("type", "object");
        node.addProperty("additionalProperties", false);
        properties.add(
            "op",
            JsonParser.parseString(
                conditional
                    ? "{\"type\":\"string\",\"enum\":[\"if\"]}"
                    : "{\"type\":\"string\",\"enum\":[\"+\",\"-\",\"*\",\"min\",\"max\",\">\",\"<\"]}"));
        JsonArray required = new JsonArray();
        required.add("op");
        for (String key :
            conditional ? new String[] {"test", "yes", "no"} : new String[] {"left", "right"}) {
          JsonObject ref = new JsonObject();
          ref.addProperty("$ref", "#/$defs/expr" + (depth - 1));
          properties.add(key, ref);
          required.add(key);
        }
        node.add("properties", properties);
        node.add("required", required);
        choices.add(node);
      }
      JsonObject expression = new JsonObject();
      expression.add("anyOf", choices);
      defs.add("expr" + depth, expression);
    }
    root =
        JsonParser.parseString(
                "{\"type\":\"object\",\"properties\":{\"program\":{\"$ref\":\"#/$defs/expr3\"},\"explanation\":{\"type\":\"string\",\"maxLength\":1000},\"protected_roadblock\":{\"type\":\"string\",\"maxLength\":1000}},\"required\":[\"program\",\"explanation\",\"protected_roadblock\"],\"additionalProperties\":false}")
            .getAsJsonObject();
    root.add("$defs", defs);
    return root.toString();
  }

  public static String source(JsonElement tree) {
    String source = emit(tree, 0, new int[] {0});
    PolicyProgram.compile(source);
    return source;
  }

  private static String emit(JsonElement tree, int depth, int[] nodes) {
    if (depth > 16 || ++nodes[0] > 128) throw new IllegalArgumentException("Syntax tree limit");
    if (tree.isJsonPrimitive()) {
      JsonPrimitive value = tree.getAsJsonPrimitive();
      if (!value.isString() && !value.isNumber())
        throw new IllegalArgumentException("Invalid leaf");
      if (value.isNumber()) {
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || Math.abs(number) > 1_000_000)
          throw new IllegalArgumentException("Number out of bounds");
        return java.math.BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
      }
      return value.getAsString();
    }
    JsonObject node = tree.getAsJsonObject();
    String op = node.get("op").getAsString();
    var keys =
        op.equals("if")
            ? java.util.List.of("test", "yes", "no")
            : java.util.List.of("left", "right");
    var expected = new java.util.HashSet<>(keys);
    expected.add("op");
    if (!node.keySet().equals(expected))
      throw new IllegalArgumentException("Unexpected syntax tree fields");
    StringBuilder code = new StringBuilder("(").append(op);
    for (String key : keys) code.append(' ').append(emit(node.get(key), depth + 1, nodes));
    return code.append(')').toString();
  }
}
