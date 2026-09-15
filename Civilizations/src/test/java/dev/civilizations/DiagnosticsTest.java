package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticsTest {
  @TempDir Path folder;

  @Tag("diagnostics")
  @Tag("tasks")
  @Tag("interaction")
  @Test
  void completionReceiptsSeparateSatisfiedStepsFromWholeProjectAndWorldChanges() {
    Settlement v = CoreTest.village();
    v.enroll("worker", 5);
    Job a = CoreTest.job(new Pos(1, 65, 1)), b = CoreTest.job(new Pos(2, 65, 1));
    v.addProject(a.project, List.of(a, b));
    List<Map<String, ?>> receipts = new ArrayList<>();
    v.observe(
        (worker, event) -> {
          if (event.containsKey("progress_type")) receipts.add(event);
        });
    assertTrue(v.claim(a.id, "worker", 100));
    assertTrue(v.done(a.id, "worker", "already_satisfied"));
    assertEquals(false, receipts.getFirst().get("project_complete"));
    assertEquals("already_satisfied", receipts.getFirst().get("effect"));
    assertTrue(v.claim(b.id, "worker", 100));
    assertTrue(v.done(b.id, "worker", "world_changed"));
    assertEquals(true, receipts.getLast().get("project_complete"));
    assertEquals(2L, receipts.getLast().get("completed_steps"));
    assertFalse(v.done(b.id, "worker", "world_changed"));
    assertEquals(2, receipts.size());
  }

  @org.junit.jupiter.api.Tag("diagnostics")
  @Test
  void debugRotationRetainsValidJsonAndFlushesQueuedEvents() throws Exception {
    List<String> errors = new ArrayList<>();
    DebugJournal journal = new DebugJournal(folder, 600, errors::add);
    for (int i = 0; i < 20; i++) journal.event("v", "w", "result", Map.of("n", i));
    journal.close();
    assertTrue(journal.awaitClosed(3000));
    assertTrue(errors.isEmpty());
    assertTrue(Files.exists(folder.resolve("events.1.jsonl")));
    assertTrue(Files.readString(folder.resolve("events.jsonl")).contains("\"n\":19"));
    try (var paths = Files.list(folder)) {
      assertTrue(paths.count() <= 5);
    }
  }

  @org.junit.jupiter.api.Tag("diagnostics")
  @Test
  void unavailableRecoveryInstructionIsRecordedAsNullWithoutInterruptingCaller() throws Exception {
    List<String> errors = new ArrayList<>();
    DebugJournal journal = new DebugJournal(folder, 10000, errors::add);
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("reason", "proposal_rejected_or_unavailable");
    evidence.put("final_instruction", null);
    journal.event("v", "w", "experiment_failure", evidence);
    evidence.put("reason", "mutated_after_enqueue");
    journal.event("v", "w", "recovery_callback_completed", Map.of("verified", true));
    journal.close();
    assertTrue(journal.awaitClosed(3000));
    assertTrue(errors.isEmpty());
    var lines = Files.readAllLines(folder.resolve("events.jsonl"));
    assertEquals(2, lines.size());
    var data =
        com.google.gson.JsonParser.parseString(lines.getFirst())
            .getAsJsonObject()
            .getAsJsonObject("data");
    assertTrue(data.has("final_instruction"));
    assertTrue(data.get("final_instruction").isJsonNull());
    assertEquals("proposal_rejected_or_unavailable", data.get("reason").getAsString());
  }

  @org.junit.jupiter.api.Tag("diagnostics")
  @Test
  void failureJournalIncludesFailedActionsButNotPlansOrClaimsOfMovement() {
    assertTrue(
        FailureEvents.isFailure("navigation_failure", Map.of("reason", "no_connected_route")));
    assertTrue(FailureEvents.isFailure("transfer", Map.of("verified", false)));
    assertTrue(FailureEvents.isFailure("inference", Map.of("stage", "timeout")));
    assertFalse(FailureEvents.isFailure("navigation", Map.of("reason", "following_mapped_route")));
  }
}
