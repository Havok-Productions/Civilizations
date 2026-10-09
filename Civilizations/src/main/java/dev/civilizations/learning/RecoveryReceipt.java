package dev.civilizations.learning;

import com.google.gson.*;
import java.util.*;

/** Dataset attribution only; a model identity is never proof that its instructions succeeded. */
final class RecoveryReceipt {
  private RecoveryReceipt() {}

  static Map<String, Object> provenance(String teacher) {
    var result = new LinkedHashMap<String, Object>();
    result.put("model", Objects.toString(teacher, "unknown"));
    result.put("identity_basis", "unresolved_label");
    try {
      JsonElement parsed = JsonParser.parseString(Objects.toString(teacher, ""));
      if (parsed.isJsonObject()) {
        result.clear();
        var object = parsed.getAsJsonObject();
        for (String field : List.of("model", "artifact", "sha256", "license", "use")) {
          var value = object.get(field);
          if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
            result.put(field, value.getAsString());
        }
        result.putIfAbsent("model", "unknown");
        String sha = Objects.toString(result.get("sha256"), "");
        result.put(
            "identity_basis",
            sha.matches("[a-fA-F0-9]{64}") ? "declared_artifact_sha256" : "unresolved_label");
      }
    } catch (JsonParseException | IllegalStateException ignored) {
      // Old skills and deterministic fixtures may contain only a human-readable teacher label.
    }
    return Collections.unmodifiableMap(result);
  }

  static Map<String, Object> context(SkillContext context) {
    return Map.of(
        "key", context.key(),
        "map_fingerprint", context.map().fingerprint,
        "origin", context.origin(),
        "goal", context.goal(),
        "reach_squared", context.reach2());
  }
}
