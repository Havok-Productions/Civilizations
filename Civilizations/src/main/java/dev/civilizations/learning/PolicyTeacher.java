package dev.civilizations.learning;

import com.google.gson.*;
import dev.coreai.*;
import java.util.*;

/** Teacher output is a proposal, never an execution receipt or a permission grant. */
public final class PolicyTeacher {
  public record Proposal(String source, String explanation, String protectedRoadblock) {}

  public record Answer(Proposal proposal, PolicyLibrary.Provenance provenance) {}

  public static final String SCHEMA = PolicySyntax.SCHEMA;
  public static final String SYSTEM =
      """
      Write a new executable villager ranking program as a JSON syntax tree in the program field. Lower numeric scores win. Cases contain observed numeric features, preferred candidate ID, current_choice, and passing. Omitted features are zero. Improve failing cases without breaking any passing case. Implement changes in program; explanation alone has no effect. Stable ties keep the first candidate, so a tie does NOT fix a failing case when the second candidate is preferred.
      Syntax: a numeric constant; a feature name string; a binary operation object {"op":"+","left":expression,"right":expression}; or {"op":"if","test":expression,"yes":expression,"no":expression}. Binary operators + - * min max > <. Comparisons return 1/0. Features: base, failures, missing, distance, repair, continuing, food, danger, vertical, detour. Programs can freely compose these expressions. Prefer a short arithmetic expression, under 12 nodes. No imports, commands, files, coordinates or functions.
      base is existing priority cost. failures counts previous failed jobs. missing counts missing ingredients. distance is squared distance. repair, continuing, food, danger are flags. vertical is absolute route height change; detour is squared distance from the waypoint. Job-only features are zero on routes, and route-only features are zero on jobs. Compute scores for the numeric examples and ensure the preferred candidate scores strictly lower than the rejected one. Preserve base ordering when other relevant features are equal.
      The host has already checked availability, protection, supported dry positions, inventory and recipes. Ranking cannot create work or fix missing native paths. Report supported suspected host-code problems in protected_roadblock, or empty if none; these are unverified hypotheses. Return JSON with program, explanation (one short sentence), protected_roadblock. No success claims. Previous rejected source and error may be supplied for correction. active_source is the current prefix-language rendering; translate its logic into the JSON tree and improve it.
      """;

  private PolicyTeacher() {}

  public static Proposal parse(String text) {
    if (text == null || text.length() > 12_000)
      throw new IllegalArgumentException("Teacher output size limit");
    JsonObject object;
    try {
      var reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(text));
      reader.setStrictness(Strictness.STRICT);
      reader.setNestingLimit(24);
      object = JsonParser.parseReader(reader).getAsJsonObject();
      if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT)
        throw new IllegalArgumentException("Trailing response data");
    } catch (java.io.IOException invalid) {
      throw new IllegalArgumentException("Invalid bounded JSON", invalid);
    }
    boolean tree = object.has("program");
    if (!object
        .keySet()
        .equals(Set.of(tree ? "program" : "source", "explanation", "protected_roadblock")))
      throw new IllegalArgumentException("Unexpected proposal fields");
    return new Proposal(
        tree ? PolicySyntax.source(object.get("program")) : string(object, "source", 4096),
        string(object, "explanation", 1000),
        string(object, "protected_roadblock", 1000));
  }

  private static String string(JsonObject object, String key, int max) {
    JsonElement value = object.get(key);
    if (!value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isString()
        || value.getAsString().length() > max) throw new IllegalArgumentException("Invalid " + key);
    return value.getAsString();
  }
}
