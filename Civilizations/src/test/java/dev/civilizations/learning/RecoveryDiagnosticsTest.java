package dev.civilizations.learning;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.civilizations.ai.*;
import dev.civilizations.core.Pos;
import dev.civilizations.navigation.NavigationMap;
import dev.coreai.SkillProgram;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RecoveryDiagnosticsTest {
  @TempDir Path root;

  @Test
  @Tag("coreai")
  @Tag("diagnostics")
  @Tag("interaction")
  void persistentCausesAndPartialEvidenceSurviveServiceReloadWithoutInventingSuccess()
      throws Exception {
    var diagnostics = new RecoveryDiagnostics(root);
    var evidence =
        Map.of(
            "goal",
            new Pos(8, 60, 0),
            "verified_steps",
            List.of(Map.of("op", "CLEAR", "block_became_air", true)));
    diagnostics.record("v", "wall", "night_sleep_interruption", evidence, 10);
    diagnostics.record("v", "wall", "night_sleep_interruption", evidence, 11);
    diagnostics.record(
        "v",
        "STRING",
        "No implemented harvesting capability for STRING",
        Map.of("resource", "STRING"),
        12);
    var restored = new RecoveryDiagnostics(root);
    String report = new Gson().toJson(restored.report("v"));
    assertTrue(report.contains("controller_interruption"));
    assertTrue(report.contains("block_became_air"));
    assertTrue(report.contains("\"occurrences\":2"));
    assertTrue(report.contains("missing_capability"));
    assertFalse(new Gson().toJson(restored.report("another-village")).contains("STRING"));
    try (var files = Files.list(root.resolve("repair-proposals"))) {
      var proposals = files.toList();
      assertEquals(2, proposals.size(), "The same cause updates its existing proposal");
      String proposal =
          Files.readString(
              proposals.stream()
                  .filter(
                      p -> {
                        try {
                          return Files.readString(p).contains("STRING");
                        } catch (Exception error) {
                          throw new RuntimeException(error);
                        }
                      })
                  .findFirst()
                  .orElseThrow());
      assertTrue(proposal.contains("GatheringActions.java"));
      assertTrue(proposal.contains("acceptance_checks"));
      assertTrue(proposal.contains("proposed_requires_reproduction_and_tests"));
    }
    assertFalse(Files.exists(root.resolve("skills/skills.json")));
  }

  @Test
  @Tag("coreai")
  @Tag("navigation")
  @Tag("interaction")
  void changedTerrainOrInventoryPermitsReadButKnownUnchangedFactsDoNotRequireAnotherProbe() {
    Pos at = new Pos(0, 60, 0), focus = at.add(2, 0, 0);
    var cells =
        Map.of(focus, new NavigationMap.Cell("CHEST", NavigationMap.Kind.OBSTACLE, "chest:east"));
    var map = new NavigationMap(at, 8, 3, cells);
    var context = SkillContext.create(map, at, focus, 1, Map.of(), "blocked");
    var memory = new RecoveryReadLedger();
    memory.observed(context, Map.of("focus", focus));
    var observe = program("OBSERVE", 2, "");
    assertTrue(memory.repeatedRead(context, observe).contains("no_new_information"));
    assertEquals("", memory.repeatedRead(context, program("OBSERVE", 3, "")));
    assertEquals(
        "",
        memory.repeatedRead(
            SkillContext.create(map, at, focus, 1, Map.of("DIRT", 1), "blocked"), observe));
    var changed =
        new NavigationMap(
            at, 8, 3, Map.of(focus, new NavigationMap.Cell("AIR", NavigationMap.Kind.AIR, "air")));
    assertEquals(
        "",
        memory.repeatedRead(
            SkillContext.create(changed, at, focus, 1, Map.of(), "blocked"), observe));
    memory.step(
        Map.of(
            "position",
            focus,
            "physical_probe",
            Map.of("material", "CHEST", "state", "chest:east")));
    assertTrue(
        memory
            .repeatedRead(context, program("CLASSIFY", 2, "OBSTACLE"))
            .contains("known_read_repeated"));
    assertEquals(
        "",
        memory.repeatedRead(
            SkillContext.create(changed, at, focus, 1, Map.of(), "blocked"),
            program("CLASSIFY", 2, "OBSTACLE")));
  }

  @Test
  @Tag("coreai")
  @Tag("inference")
  @Tag("diagnostics")
  @Tag("interaction")
  void repeatingObservationReceivesCauseAndCreatesRepairProposalWithoutEndlessInference()
      throws Exception {
    var calls = new AtomicInteger();
    var reports = new CopyOnWriteArrayList<String>();
    String source = program("OBSERVE", 2, "").source();
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
            reports.add(report);
            return source;
          }
        };
    Pos at = new Pos(0, 60, 0), goal = at.add(6, 0, 0);
    var context =
        SkillContext.create(
            new NavigationMap(at, 8, 3, Map.of()), at, goal, 1, Map.of(), "blocked");
    try (var queue = new InferenceQueue(backend, 4);
        var experiments = new RecoveryExperiments(root, queue, () -> "fixture", s -> fail(s))) {
      long now = System.currentTimeMillis();
      var trial = experiments.request("v", "w", context, now);
      trial.program.get(3, TimeUnit.SECONDS);
      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  experiments
                      .revise(trial, context, Map.of("focus", at.add(2, 0, 0)), now, true)
                      .get(3, TimeUnit.SECONDS));
      assertTrue(failure.toString().contains("observation_no_new_information"));
      assertEquals(3, calls.get(), "Initial request, one continuation, one explained correction");
      assertTrue(reports.getLast().contains("read already succeeded"));
      assertTrue(new Gson().toJson(experiments.diagnosticReport("v")).contains("observation_loop"));
      experiments.cancel(trial, "night_sleep_interruption");
    }
    assertFalse(
        Files.exists(root.resolve("skills/skills.json")),
        "Read-only attempts must not become failed or successful behavior lessons");
    assertTrue(
        new Gson().toJson(new RecoveryDiagnostics(root).report("v")).contains("observation_loop"));
  }

  private static SkillProgram program(String op, int x, String material) {
    return SkillProgram.parse(
        "{\"explanation\":\"fixture\",\"steps\":[{\"op\":\""
            + op
            + "\",\"x\":"
            + x
            + ",\"y\":0,\"z\":0,\"material\":\""
            + material
            + "\"}]}");
  }
}
