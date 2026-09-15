package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticsTest {
  @TempDir Path folder;

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
