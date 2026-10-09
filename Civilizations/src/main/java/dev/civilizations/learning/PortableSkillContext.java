package dev.civilizations.learning;

import com.google.gson.*;
import dev.civilizations.core.Pos;
import dev.civilizations.navigation.NavigationMap;
import dev.coreai.skills.SkillSituation;
import java.util.*;

/** Removes world position, never observed geometry or controller prerequisites, from skill reuse. */
final class PortableSkillContext {
  private PortableSkillContext() {}

  static SkillSituation of(SkillContext context) {
    var cells = new ArrayList<SkillSituation.Cell>(context.map().cells().size());
    for (var entry : context.map().cells().entrySet()) {
      var cell = entry.getValue();
      cells.add(
          new SkillSituation.Cell(
              relative(entry.getKey(), context.origin()),
              cell.material(),
              cell.state(),
              cell.kind().name(),
              cell.removal(),
              cell.kind() != NavigationMap.Kind.UNKNOWN));
    }
    var scope = new TreeMap<String, Object>();
    int suffix = context.key().indexOf(':');
    String version = suffix < 0 ? "" : context.key().substring(suffix + 1);
    int observationSuffix = version.indexOf(":observe:");
    if (observationSuffix >= 0) version = version.substring(0, observationSuffix);
    scope.put("controller", version);
    scope.put("radius", context.map().radius);
    scope.put("vertical", context.map().vertical);
    scope.put("map_center_relative", relative(context.map().center, context.origin()));
    // Global lesson counts and probe ordering are observations, not local prerequisites.
    // Applicable classifications remain in each captured cell's kind/removal fields.
    for (String field : List.of("verification_goal", "tunable_parameters"))
      if (context.observation().containsKey(field))
        scope.put(field, context.observation().get(field));
    return new SkillSituation(
        canonical(new Gson().toJsonTree(scope)).toString(),
        relative(context.goal(), context.origin()),
        context.reach2(),
        cells,
        context.inventory());
  }

  private static JsonElement canonical(JsonElement value) {
    if (value.isJsonObject()) {
      var sorted = new JsonObject();
      value.getAsJsonObject().keySet().stream()
          .sorted()
          .forEach(key -> sorted.add(key, canonical(value.getAsJsonObject().get(key))));
      return sorted;
    }
    if (value.isJsonArray()) {
      var array = new JsonArray();
      value.getAsJsonArray().forEach(item -> array.add(canonical(item)));
      return array;
    }
    return value;
  }

  private static SkillSituation.Point relative(Pos point, Pos origin) {
    return new SkillSituation.Point(
        Math.subtractExact(point.x(), origin.x()),
        Math.subtractExact(point.y(), origin.y()),
        Math.subtractExact(point.z(), origin.z()));
  }
}
