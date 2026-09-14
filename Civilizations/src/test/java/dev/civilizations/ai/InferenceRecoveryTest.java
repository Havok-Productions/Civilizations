package dev.civilizations.ai;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.design.Blueprint;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class InferenceRecoveryTest {
  private abstract static class Backend implements ModelBackend {
    public boolean ready() {
      return true;
    }

    public String status() {
      return "test";
    }

    public void close() {}

    public String complete(String s, String u) throws Exception {
      return "{\"action\":\"rest\"}";
    }
  }

  @org.junit.jupiter.api.Tag("inference")
  @Test
  void reasoningExhaustionRetriesOnceWithoutThinkingAndReturnsAValidatedAction() throws Exception {
    List<ReasoningMode> modes = new CopyOnWriteArrayList<>();
    var done = new CountDownLatch(1);
    var answer = new AtomicReference<Decision>();
    Backend backend =
        new Backend() {
          public String complete(String s, String u, ReasoningMode mode, String schema)
              throws Exception {
            modes.add(mode);
            if (mode != ReasoningMode.FINAL)
              throw new InvalidModelOutputException("No final answer");
            return "{\"action\":\"rest\",\"reason\":\"No available work\"}";
          }
        };
    try (var queue = new InferenceQueue(backend, 4)) {
      assertTrue(
          queue.request(
              "a",
              "report",
              Set.of(),
              d -> {
                answer.set(d);
                done.countDown();
              }));
      assertTrue(done.await(2, TimeUnit.SECONDS));
      assertNotNull(answer.get());
      assertEquals(List.of(ReasoningMode.NORMAL, ReasoningMode.FINAL), modes);
      assertTrue(queue.status().contains("final-answer-retries=1"));
    }
  }

  @org.junit.jupiter.api.Tag("inference")
  @Test
  void finalModeDisablesQwenThinkingEvenWhenUserEnablesItAndPreservesDesignBudget() {
    var config = new YamlConfiguration();
    config.set("ai.thinking", true);
    var settings = LocalRuntime.Settings.read(config);
    var normal = LocalRuntime.requestBody(settings, "s", "u", ReasoningMode.NORMAL);
    var last = LocalRuntime.requestBody(settings, "s", "u", ReasoningMode.FINAL);
    var design =
        LocalRuntime.requestBody(settings, "s", "u", ReasoningMode.FINAL, Blueprint.SCHEMA);
    assertEquals(512, normal.get("max_tokens").getAsInt());
    assertFalse(last.getAsJsonObject("chat_template_kwargs").get("enable_thinking").getAsBoolean());
    assertEquals(256, last.get("max_tokens").getAsInt());
    assertEquals(2048, design.get("max_tokens").getAsInt());
    assertThrows(InvalidModelOutputException.class, () -> LocalRuntime.finalText("{bad"));
  }

  @org.junit.jupiter.api.Tag("inference")
  @Test
  void failedFinalAnswerDoesNotLoopOrBecomeAnAction() throws Exception {
    var calls = new AtomicInteger();
    var done = new CountDownLatch(1);
    var answered = new AtomicBoolean();
    Backend backend =
        new Backend() {
          public String complete(String s, String u, ReasoningMode mode, String schema) {
            calls.incrementAndGet();
            return "{\"action\":\"work\",\"job_id\":\"invented\"}";
          }
        };
    try (var queue = new InferenceQueue(backend, 4)) {
      queue.request(
          "a",
          "report",
          Set.of(),
          d -> {
            answered.set(d != null);
            done.countDown();
          });
      assertTrue(done.await(2, TimeUnit.SECONDS));
      assertEquals(2, calls.get());
      assertFalse(answered.get());
    }
  }

  @org.junit.jupiter.api.Tag("inference")
  @Test
  void reservedAdmissionAndPriorityLetDesignPassRecentRoutineRequests() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var done = new CountDownLatch(5);
    List<String> order = new CopyOnWriteArrayList<>();
    Backend backend =
        new Backend() {
          public String complete(String s, String u, ReasoningMode mode, String schema)
              throws Exception {
            order.add(u);
            if (u.equals("first")) {
              started.countDown();
              assertTrue(release.await(3, TimeUnit.SECONDS));
            }
            return "{\"action\":\"rest\"}";
          }
        };
    try (var queue = new InferenceQueue(backend, 4)) {
      assertTrue(queue.request("first", "first", Set.of(), d -> done.countDown()));
      assertTrue(started.await(1, TimeUnit.SECONDS));
      for (int i = 0; i < 3; i++)
        assertTrue(queue.request("regular" + i, "regular" + i, Set.of(), d -> done.countDown()));
      assertFalse(queue.request("extra", "extra", Set.of(), d -> fail("Should not be admitted")));
      assertTrue(
          queue.submit(
              "design",
              "s",
              "design",
              ReasoningMode.DESIGN,
              "schema",
              System.currentTimeMillis() + 10_000,
              s -> s,
              d -> done.countDown()));
      release.countDown();
      assertTrue(done.await(3, TimeUnit.SECONDS));
      assertEquals("design", order.get(1));
      assertEquals(5, order.size());
    } finally {
      release.countDown();
    }
  }

  @org.junit.jupiter.api.Tag("inference")
  @Test
  void responsePastObservationDeadlineIsDiscarded() throws Exception {
    var done = new CountDownLatch(1);
    var value = new AtomicReference<Decision>();
    Backend backend =
        new Backend() {
          public String complete(String s, String u, ReasoningMode mode, String schema)
              throws Exception {
            new CountDownLatch(1).await(100, TimeUnit.MILLISECONDS);
            return "{\"action\":\"rest\"}";
          }
        };
    try (var queue = new InferenceQueue(backend, 4)) {
      assertTrue(
          queue.request(
              "a",
              "report",
              Set.of(),
              ReasoningMode.NORMAL,
              System.currentTimeMillis() + 50,
              d -> {
                value.set(d);
                done.countDown();
              }));
      assertTrue(done.await(2, TimeUnit.SECONDS));
      assertNull(value.get());
      assertTrue(queue.status().contains("expired"));
    }
  }
}
