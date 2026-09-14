package dev.civilizations.learning;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.ai.*;
import dev.civilizations.core.*;
import dev.coreai.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreAiIntegrationTest {
  @TempDir Path root;
  static final PolicyLibrary.Provenance TEACHER =
      new PolicyLibrary.Provenance("fixture", "test", "", "test", "verification");

  @org.junit.jupiter.api.Tag("coreai")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void actualJobAdapterUsesPromotedCodeWithoutAddingOffers() throws Exception {
    var library = new PolicyLibrary(root, VillagerPolicyCases.jobs());
    assertTrue(
        library.propose("(+ base (+ (* failures 100) (* missing 100)))", TEACHER).accepted());
    Job failed =
        new Job(
            Job.Kind.PLACE,
            "wall",
            new Pos(0, 64, 0),
            new Pos(0, 64, 1),
            "OAK_PLANKS",
            "AIR",
            null);
    failed.failures = 3;
    Job ready =
        new Job(
            Job.Kind.PLACE,
            "wall",
            new Pos(2, 64, 0),
            new Pos(2, 64, 1),
            "OAK_PLANKS",
            "AIR",
            null);
    var jobs = List.of(failed, ready);
    var ranked =
        library.rank(VillagerPolicies.jobs(jobs, new Pos(0, 64, 0), "", false, false, j -> 0));
    var ordered =
        VillagerPolicies.ordered(
            jobs,
            new CoreAiCoordinator.Choice(
                CoreAiCoordinator.Scope.JOBS, ranked.version(), ranked.options()),
            j -> j.id);
    assertSame(ready, ordered.getFirst());
    assertEquals(new HashSet<>(jobs), new HashSet<>(ordered));
  }

  @org.junit.jupiter.api.Tag("navigation")
  @org.junit.jupiter.api.Tag("coreai")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void routeProgramRanksOnlyGivenPositionsAndGuardsLargeDetours() throws Exception {
    var library = new PolicyLibrary(root, VillagerPolicyCases.routes());
    assertTrue(library.propose("(+ base (* vertical 10))", TEACHER).accepted());
    assertFalse(library.propose("(* vertical 10000)", TEACHER).accepted());
    var candidates = List.of(new Pos(10, 66, 0), new Pos(10, 64, 1));
    var ranked =
        library.rank(VillagerPolicies.routes(candidates, new Pos(0, 64, 0), new Pos(10, 64, 0)));
    assertEquals(candidates.get(1).key(), ranked.options().getFirst().id());
  }

  @org.junit.jupiter.api.Tag("coreai")
  @org.junit.jupiter.api.Tag("inference")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void teacherCannotSmuggleCommandsOrInventReceipt() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PolicyTeacher.parse(
                "{\"source\":\"base\",\"explanation\":\"done\",\"protected_roadblock\":\"\",\"command\":\"op"
                    + " player\"}"));
    var proposal =
        PolicyTeacher.parse(
            "{\"source\":\"(exec 1"
                + " 2)\",\"explanation\":\"proposal\",\"protected_roadblock\":\"\"}");
    assertThrows(IllegalArgumentException.class, () -> PolicyProgram.compile(proposal.source()));
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void typedCodeCompilesAndCannotAddHostCapabilities() {
    var proposal =
        PolicyTeacher.parse(
            "{\"program\":{\"op\":\"+\",\"left\":\"base\",\"right\":{\"op\":\"*\",\"left\":\"failures\",\"right\":100}},\"explanation\":\"Use"
                + " failure evidence\",\"protected_roadblock\":\"\"}");
    assertEquals("(+ base (* failures 100))", proposal.source());
    assertEquals(
        300, PolicyProgram.compile(proposal.source()).score(PolicyCase.features(0, "failures", 3)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PolicySyntax.source(
                com.google.gson.JsonParser.parseString(
                    "{\"op\":\"exec\",\"left\":1,\"right\":2}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PolicySyntax.source(
                com.google.gson.JsonParser.parseString(
                    "{\"op\":\"+\",\"left\":1,\"right\":2,\"path\":\"../host\"}")));
  }

  @org.junit.jupiter.api.Tag("coreai")
  @org.junit.jupiter.api.Tag("inference")
  @org.junit.jupiter.api.Tag("diagnostics")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void actualQueueProposesReplaysPromotesAndJournalsDistinctEvidence() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    ModelBackend teacher =
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
            assertTrue(report.contains("host design expectation"));
            return "{\"source\":\"(+ base (+ (* failures 100) (* missing"
                + " 100)))\",\"explanation\":\"Penalize known failures and missing"
                + " ingredients\",\"protected_roadblock\":\"A missing native route might"
                + " need a host fix\"}";
          }
        };
    List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
    try (var queue = new InferenceQueue(teacher, 4);
        var core =
            new CoreAiCoordinator(root, queue, () -> TEACHER, warnings::add, false, 60_000)) {
      var choice =
          core.rank(CoreAiCoordinator.Scope.JOBS, VillagerPolicyCases.jobs().get(3).options());
      for (int i = 0; i < 3; i++)
        core.outcome(
            core.begin("village", "worker", choice, "a"),
            false,
            Map.of("reason", "observed unreachable target"));
      core.reviewSoon();
      await(() -> core.status().contains("Replay improved"));
      await(
          () -> {
            try {
              return Files.readString(root.resolve("data/roadblocks.jsonl"))
                  .contains("unverified diagnosis");
            } catch (java.io.IOException pending) {
              return false;
            }
          });
      assertEquals(
          "b", core.rank(CoreAiCoordinator.Scope.JOBS, choice.options()).options().getFirst().id());
      core.reviewSoon();
      Thread.sleep(30);
      assertEquals(1, calls.get());
      String outcomes = Files.readString(root.resolve("data/outcomes.jsonl"));
      assertTrue(outcomes.contains("executor observation"));
      assertFalse(outcomes.contains("Penalize"));
      String proposals = Files.readString(root.resolve("data/proposals.jsonl"));
      assertTrue(proposals.contains("teacher hypothesis"));
      assertTrue(proposals.contains("fixture"));
      assertTrue(
          Files.readString(root.resolve("data/roadblocks.jsonl")).contains("unverified diagnosis"));
      assertTrue(warnings.isEmpty(), warnings.toString());
    }
  }

  @org.junit.jupiter.api.Tag("coreai")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void completionReceiptRequiresActualClaim() {
    Settlement.Data data = new Settlement.Data();
    data.world = UUID.randomUUID().toString();
    data.center = new Pos(0, 64, 0);
    Settlement village = new Settlement(data);
    Job job =
        new Job(
            Job.Kind.PLACE,
            "wall",
            new Pos(0, 64, 0),
            new Pos(0, 64, 1),
            "OAK_PLANKS",
            "AIR",
            null);
    village.addProject("wall", List.of(job));
    assertFalse(village.done(job.id, "unclaimed"));
    assertTrue(village.claim(job.id, "worker", System.currentTimeMillis()));
    assertTrue(village.done(job.id, "worker"));
    assertFalse(village.done(job.id, "worker"));
  }

  @org.junit.jupiter.api.Tag("coreai")
  @org.junit.jupiter.api.Tag("inference")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void liveRankingRequiresDistinctExecutorReceipts() throws Exception {
    ModelBackend teacher =
        new ModelBackend() {
          public boolean ready() {
            return true;
          }

          public String status() {
            return "fixture";
          }

          public void close() {}

          public String complete(String system, String report) {
            return "{\"source\":\"(- 0 base)\",\"explanation\":\"try another"
                + " choice\",\"protected_roadblock\":\"\"}";
          }
        };
    List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
    try (var queue = new InferenceQueue(teacher, 4);
        var core = new CoreAiCoordinator(root, queue, () -> TEACHER, warnings::add, false, 60000)) {
      core.liveTrials(true);
      var options =
          List.of(
              new PolicyCase.Option("a", PolicyCase.features(0)),
              new PolicyCase.Option("b", PolicyCase.features(10)));
      var old = core.rank(CoreAiCoordinator.Scope.JOBS, options);
      for (int i = 0; i < 3; i++)
        core.outcome(core.begin("v", "one", old, "a"), false, Map.of("reason", "blocked"));
      core.reviewSoon();
      await(() -> core.status().contains("One-worker live trial staged"));
      var choice = core.rank(CoreAiCoordinator.Scope.JOBS, options, "one");
      assertTrue(choice.version().startsWith("trial:"));
      var ticket = core.begin("v", "one", choice, "b");
      for (int i = 0; i < 5; i++) core.outcome(ticket, true, Map.of("observed", "arrived"));
      await(() -> core.status().contains("successes=1"));
      assertEquals("baseline", core.rank(CoreAiCoordinator.Scope.JOBS, options).version());
      for (int i = 0; i < 2; i++)
        core.outcome(core.begin("v", "one", choice, "b"), true, Map.of("observed", "arrived"));
      await(() -> core.status().contains("adopted_after_three"));
      assertEquals("b", core.rank(CoreAiCoordinator.Scope.JOBS, options).options().getFirst().id());
      assertTrue(warnings.isEmpty(), warnings.toString());
    }
  }

  @org.junit.jupiter.api.Tag("coreai")
  @org.junit.jupiter.api.Tag("tasks")
  @org.junit.jupiter.api.Tag("design")
  @org.junit.jupiter.api.Tag("interaction")
  @Test
  void ownLeasedBuildSiteAllowsPreparationWithoutReleasingOtherReservations() {
    Settlement.Data data = new Settlement.Data();
    data.world = UUID.randomUUID().toString();
    data.center = new Pos(0, 1, 0);
    Settlement village = new Settlement(data);
    Pos site = new Pos(2, 1, 0);
    Job job = new Job(Job.Kind.PLACE, "repair", site, data.center, "OAK_PLANKS", "AIR", null);
    village.addProject("repair", List.of(job));
    long now = System.currentTimeMillis();
    assertTrue(village.gatherProtected(site));
    assertFalse(village.ownsBuildSite(site, "one", now));
    assertTrue(village.claim(job.id, "one", now));
    assertTrue(village.ownsBuildSite(site, "one", now));
    assertFalse(village.ownsBuildSite(site, "two", now));
    assertFalse(village.ownsBuildSite(site.add(1, 0, 0), "one", now));
    assertFalse(village.ownsBuildSite(site, "one", Long.MAX_VALUE));
    village.release("one");
    assertFalse(village.ownsBuildSite(site, "one", now));
    assertTrue(village.gatherProtected(site));
  }

  private void await(java.util.function.BooleanSupplier ready) throws Exception {
    long end = System.nanoTime() + 5_000_000_000L;
    while (!ready.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
    assertTrue(ready.getAsBoolean());
  }
}
