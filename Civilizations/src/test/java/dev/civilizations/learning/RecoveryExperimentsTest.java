package dev.civilizations.learning;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.ai.*;
import dev.civilizations.core.Pos;
import dev.civilizations.navigation.NavigationMap;
import dev.coreai.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryExperimentsTest {
  @TempDir Path root;

  @Test
  @org.junit.jupiter.api.Tag("coreai")
  @org.junit.jupiter.api.Tag("inference")
  @org.junit.jupiter.api.Tag("interaction")
  void duplicateRevisionGetsFeedbackAndChangedInventoryAllowsReconsideration() throws Exception {
    String old =
        "{\"explanation\":\"walk\",\"steps\":[{\"op\":\"WALK\",\"x\":2,\"y\":0,\"z\":0,\"material\":\"\"}]}";
    String changed = old.replace("\"x\":2", "\"x\":3");
    var reports = new CopyOnWriteArrayList<String>();
    var calls = new AtomicInteger();
    ModelBackend backend =
        new ModelBackend() {
          public boolean ready() {
            return true;
          }

          public String status() {
            return "fixture";
          }

          public void close() {}

          public String complete(String system, String report) {
            reports.add(report);
            return calls.incrementAndGet() == 3 ? changed : old;
          }
        };
    Pos at = new Pos(0, 65, 0), goal = at.add(6, 0, 0);
    var map = new NavigationMap(at, 8, 3, Map.of());
    var context = SkillContext.create(map, at, goal, 1, Map.of(), "blocked");
    try (var queue = new InferenceQueue(backend, 4);
        var experiments = new RecoveryExperiments(root, queue, () -> "fixture", s -> fail(s))) {
      long now = System.currentTimeMillis();
      var trial = experiments.request("v", "w", context, now);
      trial.program.get(2, TimeUnit.SECONDS);
      assertEquals(
          SkillProgram.parse(changed),
          experiments
              .revise(trial, context, Map.of("reason", "blocked WALK", "failed_index", 0), now)
              .get(2, TimeUnit.SECONDS));
      assertTrue(reports.get(2).contains("unexecuted_duplicate"));
      var supplied = SkillContext.create(map, at, goal, 1, Map.of("DIRT", 4), "blocked");
      assertEquals(
          SkillProgram.parse(old),
          experiments
              .revise(trial, supplied, Map.of("reason", "support now available"), now)
              .get(2, TimeUnit.SECONDS));
      assertEquals(4, calls.get());
      experiments.cancel(trial, "fixture_end");
    }
  }

  @org.junit.jupiter.api.Tag("coreai")
  @org.junit.jupiter.api.Tag("inference")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void failedInstructionCanBeRevisedInSameTrialWithFreshEvidenceAndCorrectLearningAttribution()
      throws Exception {
    String old =
        "{\"explanation\":\"Walk\",\"steps\":[{\"op\":\"WALK\",\"x\":2,\"y\":0,\"z\":0,\"material\":\"\"}]}";
    String revised =
        "{\"explanation\":\"Clear accessible clutter"
            + " first\",\"steps\":[{\"op\":\"CLEAR\",\"x\":1,\"y\":0,\"z\":0,\"material\":\"\"}]}";
    var answer = new java.util.concurrent.atomic.AtomicReference<>(old);
    var request = new java.util.concurrent.atomic.AtomicReference<String>();
    ModelBackend backend =
        new ModelBackend() {
          public boolean ready() {
            return true;
          }

          public String status() {
            return "fixture";
          }

          public void close() {}

          public String complete(String system, String report) {
            request.set(report);
            return answer.get();
          }
        };
    Pos origin = new Pos(0, 1, 0), goal = new Pos(4, 1, 0);
    var map = new NavigationMap(origin, 8, 3, Map.of());
    var context = SkillContext.create(map, origin, goal, 1, Map.of(), "blocked");
    var fresh =
        SkillContext.create(
            map, origin, goal, 1, Map.of(), "instruction_route: no_connected_route");
    try (var queue = new InferenceQueue(backend, 4);
        var experiments = new RecoveryExperiments(root, queue, () -> "fixture", s -> fail(s))) {
      long now = System.currentTimeMillis();
      var trial = experiments.request("v", "worker", context, now);
      trial.program.get(2, TimeUnit.SECONDS);
      answer.set(revised);
      assertEquals(
          SkillProgram.parse(revised),
          experiments
              .revise(trial, fresh, Map.of("failed_index", 0, "reason", "no_connected_route"), now)
              .get(2, TimeUnit.SECONDS));
      assertTrue(request.get().contains("failed_execution"));
      assertTrue(request.get().contains("original") || request.get().contains("original_goal"));
      experiments.finish(trial, true, "fixture goal receipt", Map.of("position", goal));
      await(
          () -> {
            try {
              return Files.readString(root.resolve("data/experiments.jsonl"))
                  .contains("\"event\":\"outcome\"");
            } catch (Exception e) {
              return false;
            }
          });
      var library = new SkillLibrary(root.resolve("skills"));
      assertNull(
          library.reusable(context.key()),
          "Failed original instructions cannot be taught as successful");
      assertEquals(SkillProgram.parse(revised), library.reusable(fresh.key()));
      var second = experiments.request("v", "worker", context, now + 31000);
      second.program.get(2, TimeUnit.SECONDS);
      assertThrows(
          ExecutionException.class,
          () ->
              experiments
                  .revise(second, context, Map.of("reason", "same failure"), now + 31000)
                  .get(2, TimeUnit.SECONDS));
      experiments.cancel(second, "fixture_end");
    }
  }

  @org.junit.jupiter.api.Tag("coreai")
  @org.junit.jupiter.api.Tag("inference")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void queueCachesOnlyObservedSuccessAndSuspendsFailedReuse() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    String source =
        """
        {"explanation":"Try a detour","steps":[{"op":"WALK","x":2,"y":0,"z":0,"material":""},{"op":"VERIFY","x":0,"y":0,"z":0,"material":""}]}
        """;
    ModelBackend backend =
        new ModelBackend() {
          public boolean ready() {
            return true;
          }

          public String status() {
            return "fixture";
          }

          public void close() {}

          public String complete(String system, String report) {
            calls.incrementAndGet();
            return source;
          }
        };
    List<String> warnings = new CopyOnWriteArrayList<>();
    Pos origin = new Pos(0, 1, 0);
    var map = new NavigationMap(origin, 8, 3, Map.of());
    var context =
        SkillContext.create(map, origin, new Pos(4, 1, 0), 1, Map.of("DIRT", 2), "blocked");
    assertNotEquals(
        context.key(),
        SkillContext.create(map, origin, new Pos(4, 1, 0), 1, Map.of("DIRT", 3), "blocked").key());
    try (var queue = new InferenceQueue(backend, 4);
        var experiments = new RecoveryExperiments(root, queue, () -> "fixture", warnings::add)) {
      long now = System.currentTimeMillis();
      var first = experiments.request("v", "one", context, now);
      assertNotNull(first);
      assertNull(experiments.request("v", "two", context, now + 31000));
      assertEquals(SkillProgram.parse(source), first.program.get(2, TimeUnit.SECONDS));
      assertFalse(
          Files.exists(root.resolve("skills/skills.json")),
          "Model output alone must not become learned success");
      experiments.finish(first, true, "fixture receipt", Map.of("position", new Pos(4, 1, 0)));
      await(() -> Files.exists(root.resolve("skills/skills.json")));
      var renamedFailure =
          SkillContext.create(
              map,
              origin,
              new Pos(4, 1, 0),
              1,
              Map.of("DIRT", 2),
              "native path failed with different wording");
      assertNotEquals(context.key(), renamedFailure.key());
      assertEquals(context.reuseKey(), renamedFailure.reuseKey());
      var changedState =
          new NavigationMap(
              origin,
              8,
              3,
              Map.of(
                  origin.add(1, 0, 0),
                  new NavigationMap.Cell("OAK_DOOR", NavigationMap.Kind.OPENABLE, "open=false")));
      assertNotEquals(
          context.reuseKey(),
          SkillContext.create(
                  changedState, origin, new Pos(4, 1, 0), 1, Map.of("DIRT", 2), "blocked")
              .reuseKey());
      assertNotEquals(
          context.reuseKey(),
          SkillContext.create(map, origin, new Pos(4, 1, 0), 1, Map.of("DIRT", 3), "blocked")
              .reuseKey());
      var second = experiments.request("v", "two", renamedFailure, now + 31000);
      assertEquals(first.program.get(), second.program.get(2, TimeUnit.SECONDS));
      assertEquals(
          1,
          calls.get(),
          "Verified physical-context reuse survives changed failure wording without inference");
      experiments.finish(second, false, "fixture later failure", Map.of("position", origin));
      await(
          () -> {
            try {
              return Files.readString(root.resolve("skills/skills.json")).contains("suspended");
            } catch (Exception e) {
              return false;
            }
          });
      var third = experiments.request("v", "three", context, now + 62000);
      assertThrows(ExecutionException.class, () -> third.program.get(2, TimeUnit.SECONDS));
      assertEquals(
          3, calls.get(), "One correction request supplies feedback for the repeated proposal");
      await(
          () -> {
            try {
              return Files.readString(root.resolve("data/experiments.jsonl"))
                  .contains("same_failed_program");
            } catch (Exception e) {
              return false;
            }
          });
      String journal = Files.readString(root.resolve("data/experiments.jsonl"));
      assertTrue(journal.contains("executor observation"));
      assertTrue(journal.contains("observation"));
      assertTrue(warnings.isEmpty(), warnings.toString());
    }
  }

  private void await(java.util.function.BooleanSupplier done) throws Exception {
    long end = System.nanoTime() + 2_000_000_000L;
    while (!done.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
    assertTrue(done.getAsBoolean());
  }
}
