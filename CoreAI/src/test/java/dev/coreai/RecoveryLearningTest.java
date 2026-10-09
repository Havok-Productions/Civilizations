package dev.coreai;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.coreai.recovery.RecoveryLearning;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("coreai")
@Tag("diagnostics")
class RecoveryLearningTest {
  @TempDir Path root;

  @Tag("coreai")
  @Tag("navigation")
  @Test
  void groupedTrainingExcludesHeldoutLabelsAndNeverPromotesSimulation() throws Exception {
    String train = null, test = null;
    for (int i = 0; train == null || test == null; i++) {
      String key = "geometry-" + i;
      if (RecoveryLearning.partition(key).equals("train")) train = key;
      if (RecoveryLearning.partition(key).equals("test")) test = key;
    }
    var learner = new RecoveryLearning(root);
    var easy = List.of(.1, 0., 0., 0., 1., 0., 1., 1.);
    var hard = List.of(3., 1., 1., 0., 0., 2., 0., 0.);
    learner.outcome(new RecoveryLearning.Sample("a", train, "WALK", easy, true, "host arrival"));
    learner.outcome(new RecoveryLearning.Sample("b", train, "MINE", hard, false, "host failed"));
    double before = learner.score(easy);
    learner.outcome(new RecoveryLearning.Sample("c", test, "WALK", easy, false, "heldout failure"));
    assertEquals(before, learner.score(easy), 1e-12, "Heldout labels must not train weights");
    assertTrue(learner.score(easy) > learner.score(hard));
    assertEquals(before, new RecoveryLearning(root).score(easy), 1e-12);
    var report =
        JsonParser.parseString(Files.readString(root.resolve("candidate.json"))).getAsJsonObject();
    assertTrue(report.get("training_performed").getAsBoolean());
    assertFalse(report.get("promoted").getAsBoolean());
    learner.outcome(new RecoveryLearning.Sample("a", train, "WALK", easy, true, "host arrival"));
    assertEquals(3, Files.readAllLines(root.resolve("physical-outcomes.jsonl")).size());
    final String group = train;
    assertThrows(
        java.io.IOException.class,
        () ->
            learner.outcome(
                new RecoveryLearning.Sample("a", group, "WALK", easy, false, "conflict")));
  }
}
