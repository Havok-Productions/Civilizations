package dev.civilizations.learning;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.ai.*;
import dev.civilizations.core.Pos;
import dev.civilizations.navigation.NavigationMap;
import dev.coreai.skills.PortableSkillLibrary;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortableSkillRecoveryTest {
  @TempDir Path root;

  private static final String PROGRAM =
      """
      {"explanation":"obtain tool and clear measured stone","steps":[
        {"op":"ACQUIRE","x":0,"y":0,"z":0,"material":"WOODEN_PICKAXE"},
        {"op":"MINE","x":1,"y":0,"z":0,"material":"STONE"},
        {"op":"VERIFY","x":0,"y":0,"z":0,"material":""}]}
      """;

  @Test
  @Tag("coreai")
  @Tag("inference")
  @Tag("navigation")
  @Tag("interaction")
  void verifiedLessonTransfersAfterReloadWithoutAnotherTeacherRequest() throws Exception {
    var calls = new AtomicInteger();
    var original = context(new Pos(0, 65, 0), 0);
    var translated = context(new Pos(160, 70, -100), 19);
    try (var queue = new InferenceQueue(backend(calls, PROGRAM), 4)) {
      try (var service = service(queue)) {
        var trial =
            service.request("first village", "first worker", original, System.currentTimeMillis());
        assertNotNull(trial);
        trial.program.get(3, TimeUnit.SECONDS);
        service.finish(trial, true, "synthetic host contract", Map.of("position", original.goal()));
      }
      assertEquals(1, calls.get());
      try (var service = service(queue)) {
        var trial =
            service.request(
                "other village", "other worker", translated, System.currentTimeMillis());
        assertEquals(
            dev.coreai.SkillProgram.parse(PROGRAM), trial.program.get(3, TimeUnit.SECONDS));
        assertEquals("scripted contract, not a model", trial.teacher);
        service.finish(
            trial,
            true,
            "translated synthetic host contract",
            Map.of("position", translated.goal()));
      }
    }
    assertEquals(1, calls.get(), "matching terrain must work with no second inference");
    String receipts = Files.readString(root.resolve("data/experiments.jsonl"));
    assertTrue(receipts.contains("verified translated terrain and prerequisites"));
    assertTrue(receipts.contains("portable_skill_id"));
    var stored = new PortableSkillLibrary(root.resolve("skills/portable"));
    assertEquals(2, stored.reusable(PortableSkillContext.of(translated)).successes());
  }

  @Test
  @Tag("coreai")
  @Tag("inference")
  @Tag("navigation")
  @Tag("interaction")
  void failedTranslatedReplayCannotResurrectLegacyCopyAtOriginalLocation() throws Exception {
    var calls = new AtomicInteger();
    var reports = new CopyOnWriteArrayList<String>();
    ModelBackend backend =
        new ModelBackend() {
          public boolean ready() {
            return true;
          }

          public String status() {
            return "scripted";
          }

          public void close() {}

          public String complete(String system, String report) {
            reports.add(report);
            calls.incrementAndGet();
            return PROGRAM;
          }
        };
    var original = context(new Pos(0, 65, 0), 0);
    var translated = context(new Pos(160, 70, -100), 0);
    try (var queue = new InferenceQueue(backend, 4)) {
      try (var service = service(queue)) {
        var trial = service.request("v", "w", original, System.currentTimeMillis());
        trial.program.get(3, TimeUnit.SECONDS);
        service.finish(trial, true, "synthetic success", Map.of("position", original.goal()));
      }
      try (var service = service(queue)) {
        var trial = service.request("other", "other", translated, System.currentTimeMillis());
        trial.program.get(3, TimeUnit.SECONDS);
        service.finish(
            trial, false, "missing tool prerequisite", Map.of("reason", "tool chain stalled"));
      }
      assertEquals(1, calls.get());
      try (var service = service(queue)) {
        var trial = service.request("v", "w", original, System.currentTimeMillis());
        assertThrows(ExecutionException.class, () -> trial.program.get(3, TimeUnit.SECONDS));
        assertTrue(calls.get() > 1, "failed memory must ask for revised instructions");
      }
    }
    assertTrue(reports.get(1).contains("previous_translated_failure"));
    assertTrue(reports.get(1).contains("tool chain stalled"));
    assertNull(
        new PortableSkillLibrary(root.resolve("skills/portable"))
            .reusable(PortableSkillContext.of(original)));
  }

  @Test
  @Tag("coreai")
  @Tag("navigation")
  void translationRemovesIrrelevantHistoryButPreservesActualTerrainAndRules() {
    var original = context(new Pos(0, 65, 0), 0);
    var translated = context(new Pos(160, 70, -100), 25);
    assertNotEquals(original.reuseKey(), translated.reuseKey());
    assertEquals(
        PortableSkillContext.of(original).descriptor(),
        PortableSkillContext.of(translated).descriptor());
    var cells = new HashMap<>(translated.map().cells());
    cells.put(
        translated.origin().add(1, 0, 0),
        new NavigationMap.Cell(
            "STONE", NavigationMap.Kind.SOLID, "stone", "protected_or_reserved"));
    var changed =
        SkillContext.create(
            new NavigationMap(translated.origin(), 2, 2, cells),
            translated.origin(),
            translated.goal(),
            0,
            Map.of(),
            "blocked");
    assertNotEquals(
        PortableSkillContext.of(original).descriptor().terrainSha256(),
        PortableSkillContext.of(changed).descriptor().terrainSha256());
    var report = new HashMap<>(translated.observation());
    report.put("tunable_parameters", Map.of("walk_speed", 4));
    var tuned =
        new SkillContext(
            translated.key(),
            translated.map(),
            translated.origin(),
            translated.goal(),
            translated.reach2(),
            translated.inventory(),
            translated.failure(),
            report);
    assertNotEquals(
        PortableSkillContext.of(original).descriptor(),
        PortableSkillContext.of(tuned).descriptor());
  }

  private RecoveryExperiments service(InferenceQueue queue) throws Exception {
    return new RecoveryExperiments(
        root, queue, () -> "scripted contract, not a model", s -> fail(s));
  }

  @Test
  @Tag("coreai")
  @Tag("inference")
  @Tag("interaction")
  void legacyPersistenceFailureDoesNotSkipPortableSuspensionAtFinish() throws Exception {
    failedLegacyWrite(false);
  }

  @Test
  @Tag("coreai")
  @Tag("inference")
  @Tag("interaction")
  void legacyPersistenceFailureDoesNotSkipPortableSuspensionDuringRevision() throws Exception {
    failedLegacyWrite(true);
  }

  private void failedLegacyWrite(boolean revise) throws Exception {
    var context = context(new Pos(0, 65, 0), 0);
    var program = dev.coreai.SkillProgram.parse(PROGRAM);
    var library = new PortableSkillLibrary(root.resolve("skills/portable"));
    library.verifiedSuccess(
        PortableSkillContext.of(context),
        program,
        "scripted contract",
        "synthetic host success",
        1);
    var warnings = new CopyOnWriteArrayList<String>();
    String changed = PROGRAM.replace("WOODEN_PICKAXE", "STONE_PICKAXE");
    try (var queue = new InferenceQueue(backend(new AtomicInteger(), changed), 4);
        var service = new RecoveryExperiments(root, queue, () -> "scripted", warnings::add)) {
      var trial = service.request("v", "w", context, System.currentTimeMillis());
      trial.program.get(3, TimeUnit.SECONDS);
      Files.createDirectory(root.resolve("skills/skills.tmp"));
      if (revise) {
        service
            .revise(
                trial,
                context,
                Map.of("reason", "actual failed replay"),
                System.currentTimeMillis())
            .get(3, TimeUnit.SECONDS);
        service.cancel(trial, "contract complete");
      } else {
        service.finish(
            trial, false, "actual failed replay", Map.of("reason", "actual failed replay"));
      }
    }
    assertFalse(warnings.isEmpty(), "Injected legacy write failure must be reported");
    var reloaded = new PortableSkillLibrary(root.resolve("skills/portable"));
    assertTrue(reloaded.failedBefore(PortableSkillContext.of(context), program));
    assertNull(reloaded.reusable(PortableSkillContext.of(context)));
  }

  @Test
  @Tag("coreai")
  @Tag("inference")
  @Tag("interaction")
  void legacyFailureSuspendsExactProgramInsteadOfBestRankedAlternative() throws Exception {
    var context = context(new Pos(0, 65, 0), 0);
    var situation = PortableSkillContext.of(context);
    var failed = dev.coreai.SkillProgram.parse(PROGRAM);
    var alternative =
        dev.coreai.SkillProgram.parse(PROGRAM.replace("WOODEN_PICKAXE", "STONE_PICKAXE"));
    var library = new PortableSkillLibrary(root.resolve("skills/portable"));
    library.verifiedSuccess(situation, failed, "fixture", "synthetic success B", 1);
    library.verifiedSuccess(situation, alternative, "fixture", "synthetic success A", 2);
    library.verifiedSuccess(situation, alternative, "fixture", "synthetic success A", 3);
    new dev.coreai.SkillLibrary(root.resolve("skills"))
        .outcome(context.key(), failed, "fixture", true, "synthetic legacy B", 1);
    var calls = new AtomicInteger();
    try (var queue = new InferenceQueue(backend(calls, PROGRAM), 4);
        var service = service(queue)) {
      var trial = service.request("v", "w", context, System.currentTimeMillis());
      assertEquals(failed, trial.program.get(3, TimeUnit.SECONDS));
      service.finish(trial, false, "actual failure B", Map.of("reason", "actual failure B"));
    }
    assertEquals(0, calls.get());
    var reloaded = new PortableSkillLibrary(root.resolve("skills/portable"));
    assertTrue(reloaded.failedBefore(situation, failed));
    assertFalse(reloaded.failedBefore(situation, alternative));
    assertEquals(alternative, reloaded.reusable(situation).program());
  }

  private static ModelBackend backend(AtomicInteger calls, String answer) {
    return new ModelBackend() {
      public boolean ready() {
        return true;
      }

      public String status() {
        return "scripted";
      }

      public void close() {}

      public String complete(String system, String report) {
        calls.incrementAndGet();
        return answer;
      }
    };
  }

  private static SkillContext context(Pos origin, int unrelatedRules) {
    var cells = new HashMap<Pos, NavigationMap.Cell>();
    for (int x = -2; x <= 2; x++)
      for (int y = -1; y <= 2; y++)
        for (int z = -2; z <= 2; z++)
          cells.put(
              origin.add(x, y, z),
              new NavigationMap.Cell(
                  y < 0 ? "STONE" : "AIR",
                  y < 0 ? NavigationMap.Kind.SOLID : NavigationMap.Kind.AIR));
    cells.put(
        origin.add(1, 0, 0),
        new NavigationMap.Cell("STONE", NavigationMap.Kind.SOLID, "stone", "requires_mining"));
    var base =
        SkillContext.create(
            new NavigationMap(origin, 2, 2, cells),
            origin,
            origin.add(2, 0, 0),
            0,
            Map.of(),
            "blocked at " + origin);
    var report = new HashMap<>(base.observation());
    report.put("known_rule_count", unrelatedRules);
    report.put("physical_block_probes", List.of(origin));
    report.put("tunable_parameters", Map.of("walk_speed", 1));
    return new SkillContext(
        base.key() + ":test-version:policy-id",
        base.map(),
        origin,
        base.goal(),
        0,
        base.inventory(),
        base.failure(),
        report);
  }
}
