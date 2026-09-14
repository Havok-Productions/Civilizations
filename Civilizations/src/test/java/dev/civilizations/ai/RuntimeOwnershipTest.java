package dev.civilizations.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeOwnershipTest {
  @TempDir Path root;

  @org.junit.jupiter.api.Tag("runtime")
  @Test
  void onlyOneOwnerCanLaunchAndClosingReleasesTheLease() throws Exception {
    try (var first = new RuntimeOwnership(root, s -> {})) {
      assertThrows(java.io.IOException.class, () -> new RuntimeOwnership(root, s -> {}));
    }
    try (var second = new RuntimeOwnership(root, s -> {})) {
      assertNotNull(second);
    }
  }

  @org.junit.jupiter.api.Tag("runtime")
  @Test
  void reusedPidWithDifferentStartTimeCannotBeTerminated() throws Exception {
    long self = ProcessHandle.current().pid();
    Files.writeString(
        root.resolve("process-owner.json"),
        "{\"owner\":99999999,\"ownerStarted\":\"old\",\"child\":"
            + self
            + ",\"childStarted\":\"wrong identity\",\"executable\":\"ignored\"}");
    try (var lease =
        new RuntimeOwnership(
            root,
            s -> {
              fail("Must not kill a reused PID");
            })) {
      assertTrue(ProcessHandle.current().isAlive());
    }
  }
}
