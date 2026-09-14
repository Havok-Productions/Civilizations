package dev.civilizations.ai;

import com.google.gson.*;
import java.util.Set;

/** Offer executable objectives. Raw harvesting and chest withdrawal are executor steps. */
final class DecisionSchema {
  static String jobs(Set<String> ids) {
    JsonArray options = new JsonArray();
    if (!ids.isEmpty()) options.add(option(Set.of("work"), ids));
    options.add(option(ids.isEmpty() ? Set.of("rest", "replan") : Set.of("replan"), Set.of("")));
    if (options.size() == 1) return options.get(0).toString();
    JsonObject root = new JsonObject();
    root.add("oneOf", options);
    return root.toString();
  }

  private static JsonObject option(Set<String> actions, Set<String> ids) {
    JsonObject root = new JsonObject();
    root.addProperty("type", "object");
    JsonObject props = new JsonObject();
    props.add("action", choices(actions));
    props.add("job_id", choices(ids));
    props.add("material", choices(Set.of("")));
    JsonObject reason = new JsonObject();
    reason.addProperty("type", "string");
    props.add("reason", reason);
    root.add("properties", props);
    root.add("required", JsonParser.parseString("[\"action\",\"job_id\",\"material\",\"reason\"]"));
    root.addProperty("additionalProperties", false);
    return root;
  }

  private static JsonObject choices(Set<String> values) {
    JsonObject result = new JsonObject();
    result.addProperty("type", "string");
    JsonArray array = new JsonArray();
    values.stream().sorted().forEach(array::add);
    result.add("enum", array);
    return result;
  }
}
