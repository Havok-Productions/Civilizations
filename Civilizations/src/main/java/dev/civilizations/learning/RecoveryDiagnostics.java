package dev.civilizations.learning;

import com.google.gson.*;
import dev.coreai.agent.DiagnosticMemory;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Maps observed roadblocks to durable, actionable explanations and reviewable repair proposals. */
public final class RecoveryDiagnostics {
  public record Diagnosis(
      String kind,
      String nextAction,
      List<String> components,
      String proposedChange,
      List<String> checks) {}

  private final DiagnosticMemory memory;
  private final Path proposals;
  private final Gson json = new Gson();

  public RecoveryDiagnostics(Path root) throws IOException {
    memory = new DiagnosticMemory(root.resolve("diagnostics"));
    proposals = root.resolve("repair-proposals");
    Files.createDirectories(proposals);
  }

  public static Diagnosis diagnose(String reason) {
    String text = reason.toLowerCase(Locale.ROOT);
    if (text.contains("capacity exhausted") || text.contains("context overflow"))
      return new Diagnosis(
          "request_capacity",
          "Compact duplicated history while retaining the current goal, geometry and failure; this"
              + " request did not reach the model.",
          List.of(
              "Civilizations/src/main/java/dev/civilizations/ai/ModelContext.java",
              "Civilizations/src/main/java/dev/civilizations/ai/PromptBudget.java"),
          "Replay the recorded request through the runtime tokenizer and reserve space for a"
              + " complete answer; compact redundant diagnostics before geometry.",
          List.of(
              "Recorded request fits the configured context with output reserved",
              "Goal, protection, inventory and latest observations survive compaction"));
    if (text.contains("no implemented harvesting capability"))
      return new Diagnosis(
          "missing_capability",
          "A larger search cannot implement harvesting. Check chest stock, supported recipe"
              + " alternatives or another task; retain the unmet material requirement.",
          List.of(
              "Civilizations/src/main/java/dev/civilizations/core/HarvestCatalog.java",
              "Civilizations/src/main/java/dev/civilizations/world/GatheringActions.java"),
          "Implement or connect a real acquisition route for the reported material, including its"
              + " source, tool requirements and actual drops; do not merely mark it supported.",
          List.of(
              "Actual material reaches inventory with correct tool and source handling",
              "Existing chest stock and recipe alternatives are checked",
              "Unavailable acquisition retains the parent project"));
    if (text.contains("observation_no_new_information") || text.contains("known_read_repeated"))
      return new Diagnosis(
          "observation_loop",
          "This read already completed with unchanged observed conditions. Use the supplied result,"
              + " change an action or prerequisite, or observe a different unknown area.",
          List.of(
              "Civilizations/src/main/java/dev/civilizations/learning/RecoveryExperiments.java",
              "Civilizations/src/main/java/dev/civilizations/world/RecoveryRevision.java"),
          "Carry verified read results into subsequent decisions and avoid requesting identical"
              + " information without a world-state change.",
          List.of(
              "Repeated unchanged reads trigger corrective feedback",
              "Changed terrain, inventory or observation focus can be investigated",
              "A read is never credited as goal completion"));
    if (text.contains("cancel")
        || text.contains("night")
        || text.contains("paused")
        || text.contains("emergency")
        || text.contains("claim_lost"))
      return new Diagnosis(
          "controller_interruption",
          "The controller interrupted this attempt; preserve its goal and verified steps, then"
              + " revalidate current conditions on resumption. Interruption does not prove a bad"
              + " plan.",
          List.of(
              "Civilizations/src/main/java/dev/civilizations/world/VillagerWorker.java",
              "Civilizations/src/main/java/dev/civilizations/world/WorkerNavigation.java"),
          "Trace the recorded cancellation caller; keep routine supply selection from replacing an"
              + " active recovery, and retain legitimate sleep/emergency checkpoints.",
          List.of(
              "Routine supply work cannot hijack active recovery",
              "Sleep, danger and lost ownership still stop execution",
              "Resumption retains the goal and reports the interruption"));
    return new Diagnosis(
        "execution_roadblock",
        "Use the exact failed action and verified steps below. Recheck changed prerequisites and"
            + " choose a different feasible approach; an unsuccessful route is not proof that all"
            + " routes are impossible.",
        List.of(
            "Civilizations/src/main/java/dev/civilizations/world/WorkerSkillTrial.java",
            "Civilizations/src/main/java/dev/civilizations/world/WorkPositions.java"),
        "Reproduce the recorded action with its observed terrain and inventory; distinguish an"
            + " executor defect from unavailable geometry or materials before changing behavior.",
        List.of(
            "Reproduction identifies the failed prerequisite",
            "Alternative approach causes verified movement or block/item changes",
            "Unchanged failed instructions do not count as a new solution"));
  }

  /** Evidence is already an immutable compact receipt; full snapshots remain in the journals. */
  public void record(
      String village, String subject, String reason, Map<String, ?> evidence, long now)
      throws IOException {
    Diagnosis diagnosis = diagnose(reason);
    var note =
        memory.remember(
            village,
            diagnosis.kind(),
            subject,
            reason,
            diagnosis.nextAction(),
            json.toJson(evidence),
            now);
    var proposal = new LinkedHashMap<String, Object>();
    proposal.put("id", note.id());
    proposal.put("status", "proposed_requires_reproduction_and_tests");
    proposal.put(
        "basis",
        "host-observed diagnostic evidence; cause is a hypothesis unless established by the"
            + " receipt");
    proposal.put("occurrences", note.occurrences());
    proposal.put("last_observed", now);
    proposal.put("symptom", reason);
    proposal.put("subject", subject);
    proposal.put("components_to_inspect", diagnosis.components());
    proposal.put("proposed_change", diagnosis.proposedChange());
    proposal.put("acceptance_checks", diagnosis.checks());
    proposal.put("evidence", evidence);
    Path target = proposals.resolve(note.id() + ".json");
    Path temporary = proposals.resolve(note.id() + ".tmp");
    Files.writeString(temporary, json.toJson(proposal));
    try {
      Files.move(
          temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  public Map<String, Object> report(String village) {
    return Map.of(
        "basis",
        "Recorded host evidence; historical facts must be rechecked against current observations",
        "recent",
        memory.recent(village, 4).stream()
            .map(
                n ->
                    Map.of(
                        "kind",
                        n.kind(),
                        "subject",
                        n.subject(),
                        "reason",
                        n.reason(),
                        "next_action",
                        n.nextAction(),
                        "occurrences",
                        n.occurrences(),
                        "last_observed",
                        n.updated(),
                        "evidence",
                        JsonParser.parseString(n.evidence())))
            .toList());
  }

  public void flush() throws IOException {
    memory.flush();
  }
}
