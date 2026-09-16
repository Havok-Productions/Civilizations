package dev.coreai;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveLearningTest {
  @TempDir Path root;

  static SkillProgram program(int x) {
    return SkillProgram.parse(
        "{\"explanation\":\"detour\",\"steps\":[{\"op\":\"WALK\",\"x\":"
            + x
            + ",\"y\":0,\"z\":0,\"material\":\"\"},{\"op\":\"VERIFY\",\"x\":0,\"y\":0,\"z\":0,\"material\":\"\"}]}");
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void rejectsInvalidInstructionsAndCoordinateOverflow() {
    String source = program(2).source();
    assertThrows(
        IllegalArgumentException.class, () -> SkillProgram.parse(source.replace("WALK", "EXEC")));
    assertDoesNotThrow(() -> program(Integer.MIN_VALUE));
    assertDoesNotThrow(() -> program(21));
    assertThrows(
        IllegalArgumentException.class,
        () -> SkillProgram.parse(source.replace("\"x\":2", "\"x\":2147483648")));
    var completed = SkillProgram.parse(source.replace("VERIFY", "WALK"));
    assertEquals(3, completed.steps().size());
    assertEquals(SkillProgram.Op.VERIFY, completed.steps().getLast().op());
    assertThrows(
        IllegalArgumentException.class, () -> SkillProgram.parse(source.replace("WALK", "VERIFY")));
    assertThrows(
        IllegalArgumentException.class,
        () -> SkillProgram.parse(source.replace("WALK", "PLACE_SUPPORT")));
    assertEquals(program(2), SkillProgram.parse(source));
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void failedProgramsStayRememberedAcrossRevisionsAndRestart() throws Exception {
    var library = new SkillLibrary(root);
    library.outcome("terrain", program(2), "fixture", false, "endpoint unreachable", 1);
    library.outcome("terrain", program(3), "fixture", false, "still obstructed", 2);
    var restored = new SkillLibrary(root);
    assertTrue(restored.repeatedFailure("terrain", program(2)));
    assertTrue(
        restored.repeatedFailure(
            "terrain",
            SkillProgram.parse(program(3).source().replace("detour", "new explanation"))));
    assertFalse(restored.repeatedFailure("changed terrain", program(2)));
    assertNull(restored.reusable("terrain"));
    restored.outcome("terrain", program(4), "fixture", true, "actual arrival", 3);
    assertEquals(program(4), new SkillLibrary(root).reusable("terrain"));
    restored.outcome("terrain", program(4), "fixture", false, "later failed", 4);
    assertNull(new SkillLibrary(root).reusable("terrain"));
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void alternateFailuresCannotHideSuccessfulSkillsOrResetOlderCounters() throws Exception {
    var library = new SkillLibrary(root);
    library.outcome("same", program(2), "successful-teacher", true, "arrived", 1);
    library.outcome("same", program(3), "fixture", false, "blocked", 2);
    assertEquals(program(2), new SkillLibrary(root).reusable("same"));
    assertEquals("successful-teacher", new SkillLibrary(root).reusableExperience("same").teacher());
    assertEquals(
        2, library.outcome("same", program(2), "fixture", true, "arrived again", 3).successes());
    assertEquals(
        2, library.outcome("same", program(3), "fixture", false, "still blocked", 4).failures());
    assertEquals(program(2), new SkillLibrary(root).reusable("same"));
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void replayRegressionCanPilotOnOneWorkerButCannotClaimAdoption() throws Exception {
    var library = new PolicyLibrary(root, PolicyLibraryTest.guards());
    var options = PolicyLibraryTest.guards().get(1).options();
    assertTrue(library.stageTrial("(- 0 base)", PolicyLibraryTest.TEACHER).accepted());
    assertEquals("baseline", library.version().id());
    var control = library.rankForWorker(options, "one");
    assertEquals("first", control.options().getFirst().id());
    assertEquals("first", library.rankForWorker(options, "two").options().getFirst().id());
    assertEquals("", library.trialOutcome(control.version(), "two", measured("same-work", 1000)));
    for (int i = 0; i < 3; i++) {
      assertEquals("baseline", library.version().id());
      var baseline = library.rankForWorker(options, "one");
      assertTrue(baseline.version().startsWith("control:"));
      library.trialOutcome(baseline.version(), "one", measured("same-work", 1000));
      var pilot = library.rankForWorker(options, "one");
      assertEquals("second", pilot.options().getFirst().id());
      assertEquals(
          i == 2
              ? "live_trial_adopted_after_three_measured_improvements"
              : "live_trial_pair_improved",
          library.trialOutcome(pilot.version(), "one", measured("same-work", 700)));
    }
    assertEquals(library.version(), new PolicyLibrary(root, PolicyLibraryTest.guards()).version());
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void environmentalFailureAndNoOpRemainInconclusiveAndDoNotBlacklistCandidate() throws Exception {
    var library = new PolicyLibrary(root, PolicyLibraryTest.guards());
    library.stageTrial("(+ base 1)", PolicyLibraryTest.TEACHER);
    assertEquals(
        "baseline",
        library.rankForWorker(PolicyLibraryTest.guards().get(1).options(), "one").version());
    library.rollback();
    library.stageTrial("(- 0 base)", PolicyLibraryTest.TEACHER);
    var pilot = library.rankForWorker(PolicyLibraryTest.guards().get(1).options(), "one");
    assertTrue(
        library
            .trialOutcome(
                pilot.version(), "one", PolicyMeasurement.ignored("player changed terrain"))
            .startsWith("live_trial_inconclusive"));
    assertEquals("baseline", library.version().id());
    assertTrue(
        new PolicyLibrary(root, PolicyLibraryTest.guards())
            .stageTrial("(- 0 base)", PolicyLibraryTest.TEACHER)
            .accepted());
  }

  private static PolicyMeasurement measured(String context, double cost) {
    return new PolicyMeasurement(context, cost, true, true, "verified execution");
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void unmatchedAndUnimprovedSuccessesCannotPromote() throws Exception {
    var library = new PolicyLibrary(root, PolicyLibraryTest.guards());
    var options = PolicyLibraryTest.guards().get(1).options();
    library.stageTrial("(- 0 base)", PolicyLibraryTest.TEACHER);
    for (int i = 0; i < 8; i++) {
      var choice = library.rankForWorker(options, "worker");
      library.trialOutcome(choice.version(), "worker", measured(i % 2 == 0 ? "ore" : "bed", 1000));
    }
    assertEquals("baseline", library.version().id());
    for (int i = 0; i < 6; i++) {
      var choice = library.rankForWorker(options, "worker");
      library.trialOutcome(choice.version(), "worker", measured("same", 1000));
    }
    assertEquals("baseline", library.version().id());
    assertTrue(library.trialStatus().contains("improvements=0"));
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void rollbackRestoresLastVerifiedPolicyAcrossRestart() throws Exception {
    var library = new PolicyLibrary(root, PolicyLibraryTest.guards());
    library.propose("(+ base (* failures 100))", PolicyLibraryTest.TEACHER);
    String previous = library.version().id();
    library.outcome(previous, true);
    var options = PolicyLibraryTest.guards().get(1).options();
    library.stageTrial("(- 0 base)", PolicyLibraryTest.TEACHER);
    for (int i = 0; i < 6; i++) {
      var choice = library.rankForWorker(options, "one");
      library.trialOutcome(choice.version(), "one", measured("same", i % 2 == 0 ? 1000 : 700));
    }
    assertNotEquals(previous, library.version().id());
    var restored = new PolicyLibrary(root, PolicyLibraryTest.guards());
    restored.rollback();
    assertEquals(previous, restored.version().id());
    assertEquals(previous, new PolicyLibrary(root, PolicyLibraryTest.guards()).version().id());
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void attributedCandidateFailureCannotBeErasedByFasterSuccessfulPairs() throws Exception {
    var library = new PolicyLibrary(root, PolicyLibraryTest.guards());
    var options = PolicyLibraryTest.guards().get(1).options();
    library.stageTrial("(- 0 base)", PolicyLibraryTest.TEACHER);
    var control = library.rankForWorker(options, "one");
    library.trialOutcome(control.version(), "one", measured("work", 1000));
    var candidate = library.rankForWorker(options, "one");
    library.trialOutcome(
        candidate.version(),
        "one",
        new PolicyMeasurement("work", 1000, true, false, "verified failed choice"));
    for (int i = 0; i < 3; i++) {
      library.trialOutcome(control.version(), "one", measured("work", 1000));
      library.trialOutcome(candidate.version(), "one", measured("work", 700));
    }
    assertEquals("baseline", library.version().id());
    assertTrue(library.trialStatus().contains("candidate_failures=1"));
    assertTrue(library.trialStatus().contains("regressions=1"));
    library.trialOutcome(
        candidate.version(), "one", new PolicyMeasurement("work", 1000, true, false, "failed"));
    assertTrue(
        library
            .trialOutcome(
                candidate.version(),
                "one",
                new PolicyMeasurement("work", 1000, true, false, "failed"))
            .contains("suspended"));
    assertEquals("none", library.trialStatus());
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void failedControlAndSuccessfulCandidateAreAComparisonNotADiscardedFailure() throws Exception {
    var library = new PolicyLibrary(root, PolicyLibraryTest.guards());
    var options = PolicyLibraryTest.guards().get(1).options();
    library.stageTrial("(- 0 base)", PolicyLibraryTest.TEACHER);
    for (int i = 0; i < 3; i++) {
      var control = library.rankForWorker(options, "one");
      library.trialOutcome(
          control.version(),
          "one",
          new PolicyMeasurement("work", 1000, true, false, "failed choice"));
      var candidate = library.rankForWorker(options, "one");
      library.trialOutcome(candidate.version(), "one", measured("work", 1200));
    }
    assertNotEquals("baseline", library.version().id());
  }
}
