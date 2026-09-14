package dev.civilizations.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class ThinkingModelsTest {
  static class Engine implements ModelBackend {
    boolean available = true;
    String output = "{\"action\":\"work\",\"job_id\":\"repair\"}";
    boolean fail;
    List<ReasoningMode> modes = new ArrayList<>();

    public boolean ready() {
      return available;
    }

    public String status() {
      return "fixture engine";
    }

    public String complete(String s, String u) throws Exception {
      return output;
    }

    public String complete(String s, String u, ReasoningMode m, String schema, long deadline)
        throws Exception {
      modes.add(m);
      if (fail) throw new IOException("timeout");
      return output;
    }

    public void close() {}
  }

  @org.junit.jupiter.api.Tag("inference")
  @Test
  void recoveryUsesDeepAndUnavailableDeepDoesNotBlockWork() throws Exception {
    var qwen = new Engine();
    var deep = new Engine();
    try (var router = new ThinkingModels(qwen, deep, (a, b) -> {})) {
      router.complete("s", "u", ReasoningMode.RECOVERY, null, Long.MAX_VALUE);
      assertEquals(List.of(ReasoningMode.RECOVERY), deep.modes);
      assertTrue(qwen.modes.isEmpty());
      deep.available = false;
      router.complete("s", "u", ReasoningMode.RECOVERY, null, Long.MAX_VALUE);
      assertEquals(List.of(ReasoningMode.RECOVERY), qwen.modes);
    }
  }

  @org.junit.jupiter.api.Tag("inference")
  @Test
  void invalidOrTimedOutPrimaryEscalatesOnceAndValidatesDeepAnswer() throws Exception {
    for (boolean timeout : List.of(false, true)) {
      var qwen = new Engine();
      var deep = new Engine();
      qwen.fail = timeout;
      qwen.output = "{\"action\":\"work\",\"job_id\":\"invented\"}";
      var result = new AtomicReference<Decision>();
      var done = new CountDownLatch(1);
      try (var queue = new InferenceQueue(new ThinkingModels(qwen, deep, (a, b) -> {}), 4)) {
        assertTrue(
            queue.request(
                "v",
                "repair",
                Set.of("repair"),
                d -> {
                  result.set(d);
                  done.countDown();
                }));
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals("repair", result.get().jobId());
        assertEquals(1, qwen.modes.size());
        assertEquals(List.of(ReasoningMode.RECOVERY), deep.modes);
      }
    }
  }

  @org.junit.jupiter.api.Tag("inference")
  @Test
  void InvalidDeepCannotBecomeAnActionOrLoop() throws Exception {
    var qwen = new Engine();
    var deep = new Engine();
    qwen.fail = true;
    deep.output = "invalid";
    var done = new CountDownLatch(1);
    var result = new AtomicReference<Decision>();
    try (var queue = new InferenceQueue(new ThinkingModels(qwen, deep, (a, b) -> {}), 4)) {
      queue.request(
          "v",
          "u",
          Set.of("repair"),
          d -> {
            result.set(d);
            done.countDown();
          });
      assertTrue(done.await(2, TimeUnit.SECONDS));
      assertNull(result.get());
      assertEquals(1, qwen.modes.size());
      assertEquals(1, deep.modes.size());
    }
  }
}
