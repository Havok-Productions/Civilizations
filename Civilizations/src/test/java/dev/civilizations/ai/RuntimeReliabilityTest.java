package dev.civilizations.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeReliabilityTest {
  @TempDir Path root;

  @org.junit.jupiter.api.Tag("runtime")
  @Test
  void launchesUseSeparateLogsAndLeaveAnOpenLegacyLogUntouched() throws Exception {
    Path legacy = root.resolve("runtime.log");
    Files.writeString(legacy, "previous engine output");
    List<String> messages = new ArrayList<>();
    try (var channel = FileChannel.open(legacy, StandardOpenOption.WRITE);
        var lock = channel.lock()) {
      var first = RuntimeLogs.output(root, messages::add);
      var second = RuntimeLogs.output(root, messages::add);
      assertEquals(ProcessBuilder.Redirect.Type.APPEND, first.type());
      assertNotEquals(first.file(), second.file());
      assertTrue(Files.isRegularFile(first.file().toPath()));
      assertTrue(first.file().toPath().startsWith(root.resolve("logs")));
      assertEquals(2, messages.size());
    }
    assertEquals("previous engine output", Files.readString(legacy));
    assertFalse(Files.exists(root.resolve("runtime.previous.log")));
  }

  @org.junit.jupiter.api.Tag("runtime")
  @Test
  void UnwritableLogLocationDoesNotBlockLaunch() throws Exception {
    Files.writeString(root.resolve("logs"), "obstruction");
    List<String> messages = new ArrayList<>();
    assertSame(ProcessBuilder.Redirect.DISCARD, RuntimeLogs.output(root, messages::add));
    assertTrue(messages.getFirst().contains("continuing"));
  }

  @org.junit.jupiter.api.Tag("runtime")
  @Test
  void failuresRetryBeyondThreeAttemptsWithCappedBackoff() {
    var recovery = new RuntimeRecovery();
    for (long expected : new long[] {15, 30, 60, 120, 240, 300, 300, 300})
      assertEquals(expected, recovery.retrySeconds());
    for (int i = 0; i < 100; i++) assertEquals(300, recovery.retrySeconds());
  }

  @org.junit.jupiter.api.Tag("runtime")
  @Test
  void onlyConsecutiveUnhealthyChecksRestartAnEngine() {
    var recovery = new RuntimeRecovery();
    assertFalse(recovery.restartForHealth(false, 0));
    assertFalse(recovery.restartForHealth(false, 1));
    assertFalse(recovery.restartForHealth(true, 2));
    assertFalse(recovery.restartForHealth(false, 3));
    assertFalse(recovery.restartForHealth(false, 4));
    assertTrue(recovery.restartForHealth(false, 5));
  }

  @org.junit.jupiter.api.Tag("runtime")
  @Test
  void briefStartupSuccessDoesNotResetCrashLoopBackoff() {
    var recovery = new RuntimeRecovery();
    assertEquals(15, recovery.retrySeconds());
    recovery.restartForHealth(true, 0);
    recovery.restartForHealth(true, TimeUnit.SECONDS.toNanos(59));
    assertEquals(30, recovery.retrySeconds());
    recovery.restartForHealth(true, 0);
    recovery.restartForHealth(true, TimeUnit.SECONDS.toNanos(60));
    assertEquals(15, recovery.retrySeconds());
  }

  @org.junit.jupiter.api.Tag("runtime")
  @Test
  void startupFailureReportsScheduledRecoveryAndCloseIsFinal() throws Exception {
    var config = new YamlConfiguration();
    config.set("ai.backend", "invalid-test-backend");
    var retry = new CountDownLatch(1);
    var messages = new CopyOnWriteArrayList<String>();
    var runtime =
        new LocalRuntime(
            LocalRuntime.Settings.read(config),
            root,
            message -> {
              messages.add(message);
              if (message.contains("retrying in")) retry.countDown();
            });
    try {
      runtime.start();
      runtime.start();
      assertTrue(retry.await(3, TimeUnit.SECONDS));
      assertFalse(runtime.ready());
      assertTrue(runtime.status().contains("retrying in 15s"));
      assertEquals(1, messages.size());
    } finally {
      runtime.close();
    }
    runtime.start();
    runtime.close();
    assertEquals("stopped", runtime.status());
    assertFalse(runtime.ready());
  }
}
