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
      var second = experiments.request("v", "two", context, now + 31000);
      assertEquals(first.program.get(), second.program.get(2, TimeUnit.SECONDS));
      assertEquals(1, calls.get(), "Verified same-context reuse requires no inference");
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
      assertEquals(2, calls.get());
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
