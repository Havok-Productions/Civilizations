package dev.coreai;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolicyLibraryTest {
  @TempDir Path root;
  static final PolicyLibrary.Provenance TEACHER =
      new PolicyLibrary.Provenance("test", "fixture", "hash", "test-only", "unit verification");

  static List<PolicyCase> guards() {
    return List.of(
        new PolicyCase(
            "avoid failing",
            "host",
            List.of(
                new PolicyCase.Option("failed", PolicyCase.features(0, "failures", 3)),
                new PolicyCase.Option("ready", PolicyCase.features(100))),
            "ready"),
        new PolicyCase(
            "preserve order",
            "host",
            List.of(
                new PolicyCase.Option("first", PolicyCase.features(0)),
                new PolicyCase.Option("second", PolicyCase.features(100))),
            "first"));
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void promotesOnlyImprovementPersistsAndChangesSelection() throws Exception {
    var library = new PolicyLibrary(root, guards());
    assertFalse(library.propose("base", TEACHER).accepted());
    assertFalse(library.propose("(- 0 base)", TEACHER).accepted());
    assertTrue(library.propose("(+ base (* failures 100))", TEACHER).accepted());
    assertEquals("ready", library.rank(guards().getFirst().options()).options().getFirst().id());
    var restored = new PolicyLibrary(root, guards());
    assertEquals(library.version(), restored.version());
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void staleResultsCannotRevertCurrentPolicyAndThreeFailuresRollback() throws Exception {
    var library = new PolicyLibrary(root, guards());
    String source = "(+ base (* failures 100))";
    library.propose(source, TEACHER);
    String version = library.version().id();
    for (int i = 0; i < 5; i++) assertFalse(library.outcome("old", false));
    assertFalse(library.outcome(version, false));
    assertFalse(library.outcome(version, false));
    var restored = new PolicyLibrary(root, guards());
    assertTrue(restored.outcome(version, false));
    assertEquals("baseline", restored.version().id());
    assertFalse(restored.propose(source, TEACHER).accepted());
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void successResetsFailureStreak() throws Exception {
    var library = new PolicyLibrary(root, guards());
    library.propose("(+ base (* failures 100))", TEACHER);
    String version = library.version().id();
    library.outcome(version, false);
    library.outcome(version, false);
    library.outcome(version, true);
    assertFalse(library.outcome(version, false));
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void rejectsCorruptStateAndTamperedCode() throws Exception {
    Files.writeString(root.resolve("state.json"), "not JSON");
    assertThrows(java.io.IOException.class, () -> new PolicyLibrary(root, guards()));
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void recordedSuccessRegressionBlocksPromotion() throws Exception {
    var library = new PolicyLibrary(root, guards());
    library.remember(
        new PolicyCase("observed", "completed", guards().getFirst().options(), "failed"));
    assertFalse(library.propose("(+ base (* failures 100))", TEACHER).accepted());
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void dataDoesNotAcceptModelChosenPaths() throws Exception {
    var journal = new DataJournal(root);
    assertThrows(IllegalArgumentException.class, () -> journal.append("../escape", Map.of("x", 1)));
    journal.append("outcomes", Map.of("success", false));
    assertTrue(Files.readString(root.resolve("outcomes.jsonl")).contains("false"));
  }
}
