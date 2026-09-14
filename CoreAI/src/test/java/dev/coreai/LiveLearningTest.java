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
    assertThrows(IllegalArgumentException.class, () -> program(Integer.MIN_VALUE));
    assertThrows(IllegalArgumentException.class, () -> program(21));
    assertThrows(
        IllegalArgumentException.class, () -> SkillProgram.parse(source.replace("VERIFY", "WALK")));
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
  void replayRegressionCanPilotOnOneWorkerButCannotClaimAdoption() throws Exception {
    var library = new PolicyLibrary(root, PolicyLibraryTest.guards());
    var options = PolicyLibraryTest.guards().get(1).options();
    assertTrue(library.stageTrial("(- 0 base)", PolicyLibraryTest.TEACHER).accepted());
    assertEquals("baseline", library.version().id());
    var pilot = library.rankForWorker(options, "one");
    assertEquals("second", pilot.options().getFirst().id());
    assertEquals("first", library.rankForWorker(options, "two").options().getFirst().id());
    assertEquals("", library.trialOutcome(pilot.version(), "two", true));
    assertEquals("live_trial_progress", library.trialOutcome(pilot.version(), "one", true));
    assertEquals("baseline", library.version().id());
    library.trialOutcome(pilot.version(), "one", true);
    assertEquals(
        "live_trial_adopted_after_three_observed_outcomes",
        library.trialOutcome(pilot.version(), "one", true));
    assertEquals(library.version(), new PolicyLibrary(root, PolicyLibraryTest.guards()).version());
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void oneFailedPilotSuspendsCandidateAndNoOpDoesNotCollectEvidence() throws Exception {
    var library = new PolicyLibrary(root, PolicyLibraryTest.guards());
    library.stageTrial("(+ base 1)", PolicyLibraryTest.TEACHER);
    assertEquals(
        "baseline",
        library.rankForWorker(PolicyLibraryTest.guards().get(1).options(), "one").version());
    library.rollback();
    library.stageTrial("(- 0 base)", PolicyLibraryTest.TEACHER);
    var pilot = library.rankForWorker(PolicyLibraryTest.guards().get(1).options(), "one");
    assertEquals("live_trial_suspended", library.trialOutcome(pilot.version(), "one", false));
    assertEquals("baseline", library.version().id());
    assertFalse(
        new PolicyLibrary(root, PolicyLibraryTest.guards())
            .stageTrial("(- 0 base)", PolicyLibraryTest.TEACHER)
            .accepted());
  }
}
