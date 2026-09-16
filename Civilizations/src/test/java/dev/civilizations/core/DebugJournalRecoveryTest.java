package dev.civilizations.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DebugJournalRecoveryTest {
  @TempDir Path root;

  @Test
  @Tag("diagnostics")
  void failedAppendRetainsEventAndResumesTheSameWriterAfterFileBecomesAvailable() throws Exception {
    Path blocked = root.resolve("events.jsonl");
    Files.createDirectory(blocked);
    var failed = new CountDownLatch(1);
    var warnings = new CopyOnWriteArrayList<String>();
    var journal =
        new DebugJournal(
            root,
            100000,
            message -> {
              warnings.add(message);
              if (message.contains("retained for retry")) failed.countDown();
            });
    try {
      journal.event("v", "w", "first", Map.of("n", 1));
      assertTrue(failed.await(3, TimeUnit.SECONDS));
      journal.event("v", "w", "second", Map.of("n", 2));
      Files.delete(blocked);
    } finally {
      journal.close();
    }
    assertTrue(journal.awaitClosed(5000));
    var lines = Files.readAllLines(blocked);
    assertEquals(2, lines.size());
    assertTrue(lines.get(0).contains("\"n\":1"));
    assertTrue(lines.get(1).contains("\"n\":2"));
    assertTrue(warnings.stream().anyMatch(s -> s.contains("recovered")));
    assertFalse(warnings.stream().anyMatch(s -> s.contains("unsaved")));
  }

  @Test
  @Tag("diagnostics")
  void partiallyCompletedRotationResumesWithoutMovingAnArchiveTwice() throws Exception {
    Files.writeString(root.resolve("events.jsonl"), "current\n");
    for (int i = 1; i <= 3; i++)
      Files.writeString(root.resolve("events." + i + ".jsonl"), "old" + i + "\n");
    var once = new AtomicBoolean();
    var file =
        new RotatingJournalFile(
            root,
            1,
            (from, to) -> {
              if (from.getFileName().toString().equals("events.1.jsonl") && !once.getAndSet(true))
                throw new IOException("simulated archive sharing violation");
              Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            });
    byte[] pending = "new\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertThrows(IOException.class, () -> file.append(pending));
    file.append(pending);
    assertEquals("new\n", Files.readString(root.resolve("events.jsonl")));
    assertEquals("current\n", Files.readString(root.resolve("events.1.jsonl")));
    for (int i = 2; i <= 4; i++)
      assertEquals(
          "old" + (i - 1) + "\n", Files.readString(root.resolve("events." + i + ".jsonl")));
  }
}
