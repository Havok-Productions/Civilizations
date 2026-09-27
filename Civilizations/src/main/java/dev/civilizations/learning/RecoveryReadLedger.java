package dev.civilizations.learning;

import com.google.gson.*;
import dev.civilizations.core.Pos;
import dev.coreai.SkillProgram;
import java.util.*;

/**
 * Read receipts for one attempt. Different focuses and changed physical conditions stay eligible.
 */
final class RecoveryReadLedger {
  private final Map<Pos, String> observations = new HashMap<>();
  private final Map<Pos, JsonObject> classifications = new HashMap<>();
  private final ArrayDeque<Map<String, ?>> verified = new ArrayDeque<>();
  private final Gson json = new Gson();

  void observed(SkillContext context, Map<String, ?> request) {
    if (request.get("focus") != null) {
      Pos focus = json.fromJson(json.toJson(request.get("focus")), Pos.class);
      observations.put(focus, condition(context));
    }
  }

  void step(Map<String, ?> evidence) {
    JsonObject receipt = json.toJsonTree(evidence).getAsJsonObject();
    if (receipt.has("physical_probe") && receipt.has("position"))
      classifications.put(
          json.fromJson(receipt.get("position"), Pos.class),
          receipt.getAsJsonObject("physical_probe").deepCopy());
    // Physical read facts and executed instructions are useful even when the full route fails.
    if (receipt.has("physical_probe") || receipt.has("verified")) {
      var summary = new LinkedHashMap<String, Object>();
      for (String field :
          List.of(
              "instruction",
              "position",
              "actual_position",
              "inventory",
              "physical_probe",
              "verified")) if (evidence.containsKey(field)) summary.put(field, evidence.get(field));
      verified.addLast(Map.copyOf(summary));
      // This is the prompt window, not evidence retention: every receipt remains in the journal.
      while (verified.size() > 4) verified.removeFirst();
    }
  }

  String repeatedRead(SkillContext context, SkillProgram program) {
    var first = program.steps().getFirst();
    if (first.op() != SkillProgram.Op.OBSERVE && first.op() != SkillProgram.Op.CLASSIFY) return "";
    Pos focus;
    try {
      focus =
          new Pos(
              Math.addExact(context.origin().x(), first.x()),
              Math.addExact(context.origin().y(), first.y()),
              Math.addExact(context.origin().z(), first.z()));
    } catch (ArithmeticException overflow) {
      return "";
    }
    if (first.op() == SkillProgram.Op.OBSERVE && condition(context).equals(observations.get(focus)))
      return "observation_no_new_information: " + focus.key();
    JsonObject known = classifications.get(focus);
    if (first.op() == SkillProgram.Op.CLASSIFY && known != null) {
      var cell = context.map().cell(focus);
      // Require a current observation before concluding that a previous classification still
      // applies.
      if (!cell.state().isEmpty()
          && cell.state().equals(known.get("state").getAsString())
          && cell.material().equals(known.get("material").getAsString()))
        return "known_read_repeated: " + focus.key() + " is " + cell.material();
    }
    return "";
  }

  List<Map<String, ?>> verified() {
    return List.copyOf(verified);
  }

  private String condition(SkillContext context) {
    return context.map().fingerprint
        + ":"
        + context.origin()
        + ":"
        + context.goal()
        + ":"
        + context.reach2()
        + ":"
        + new TreeMap<>(context.inventory())
        + ":"
        + json.toJson(context.observation().get("requested_observation"))
        + ":"
        + json.toJson(context.observation().get("tunable_parameters"));
  }
}
