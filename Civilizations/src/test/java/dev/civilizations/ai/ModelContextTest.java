package dev.civilizations.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;

class ModelContextTest {
  private static JsonObject report() {
    JsonObject report =
        JsonParser.parseString(
                """
                {"terrain":{"unknown":"?","rows":["....??##"]},"allowed_kinds":["wall"],
                 "survey_origin":{"x":-4100,"y":70,"z":-1300},"stock":{"COAL":6},
                 "salvage_existing_proposal":{"proposal_id":"retained-wall","original":{"points":[{"x":4,"z":7}]},
                  "latest_attempt":{"points":[{"x":5,"z":7}]},"failure":"unsupported target"},
                 "validated_site_examples":[{"blueprint":{"kind":"wall"}}],
                 "projects":{"project-one":24,"project-two":16}}
                """)
            .getAsJsonObject();
    var feedback = new JsonArray();
    for (int i = 0; i < 30; i++) feedback.add("earlier failure " + i + " context ".repeat(60));
    report.add("feedback", feedback);
    return report;
  }

  @Test
  @Tag("inference")
  @Tag("design")
  @Tag("interaction")
  void queueSendsFocusedSnapshotAndRetainsGoalGeometryCurrentFailureAndStock() throws Exception {
    JsonObject original = report();
    String full = original.toString();
    var received = new CompletableFuture<String>();
    ModelBackend backend =
        new ModelBackend() {
          public boolean ready() {
            return true;
          }

          public String status() {
            return "fixture";
          }

          public void close() {}

          public String complete(String system, String user) {
            received.complete(user);
            return "ok";
          }
        };
    try (var queue = new InferenceQueue(backend, 4)) {
      queue.submit(
          "design:fixture",
          "system",
          full,
          ReasoningMode.DESIGN,
          "{}",
          System.currentTimeMillis() + 2000,
          s -> s,
          s -> {});
      var sent = JsonParser.parseString(received.get(2, TimeUnit.SECONDS)).getAsJsonObject();
      assertTrue(sent.toString().length() < full.length() / 3);
      for (String field :
          List.of(
              "terrain",
              "survey_origin",
              "stock",
              "salvage_existing_proposal",
              "validated_site_examples")) assertEquals(original.get(field), sent.get(field), field);
      assertEquals(40, sent.get("project_job_count").getAsInt());
      assertFalse(sent.has("projects"));
      assertEquals(2, sent.getAsJsonArray("feedback").size());
      assertEquals(full, original.toString(), "Archived observations are not mutated");
    }
  }

  @Test
  @Tag("inference")
  @Tag("runtime")
  @Tag("interaction")
  void budgetCountsTemplatedRequestAndRemovesOnlyHistoryToReserveTheAnswer() throws Exception {
    var settings = LocalRuntime.Settings.read(new YamlConfiguration());
    var body =
        LocalRuntime.requestBody(
            settings,
            "system",
            ModelContext.compact(report().toString()),
            ReasoningMode.DESIGN,
            "{\"type\":\"object\"}");
    var prepared =
        PromptBudget.prepare(
            body,
            4096,
            request -> {
              assertEquals(body.get("response_format"), request.get("response_format"));
              var user =
                  JsonParser.parseString(
                          request
                              .getAsJsonArray("messages")
                              .get(1)
                              .getAsJsonObject()
                              .get("content")
                              .getAsString())
                      .getAsJsonObject();
              return user.has("feedback") ? 3000 : 1000;
            });
    assertEquals(1, prepared.reductions());
    assertTrue(prepared.promptTokens() + prepared.outputTokens() + 64 <= 4096);
    assertTrue(prepared.body().toString().contains("retained-wall"));
    assertTrue(prepared.body().toString().contains("unsupported target"));
    var error =
        assertThrows(IOException.class, () -> PromptBudget.prepare(body, 2048, request -> 2500));
    assertTrue(error.getMessage().contains("prompt=2500"));
    assertTrue(
        body.toString().contains("earlier failure"),
        "Original request remains available for another backend");
  }

  @Test
  @Tag("inference")
  @Tag("tasks")
  @Tag("interaction")
  void delayedAdviceCannotReplaceANewGoalOrOutliveItsObservation() {
    var decision = new Decision("work", "job", "", "Continue wall");
    var handoff = new DecisionHandoff(decision, "wall", 200);
    assertSame(decision, handoff.current("wall", 199));
    assertNull(handoff.current("house", 199));
    assertNull(handoff.current("wall", 201));
    var worker =
        JsonParser.parseString(
                """
                {"jobs":[{"id":"job","target":{"x":2,"y":70,"z":1}}],"inventory":{"OAK_LOG":2},
                 "current_project":"wall","unfinished_steps":[{"job":"supply","parent":"job"}],
                 "recovery_problem":"need coal","missing_ingredients_by_job":{"job":{"COAL":1}}}
                """)
            .getAsJsonObject();
    var compact = JsonParser.parseString(ModelContext.compact(worker.toString())).getAsJsonObject();
    worker.entrySet().forEach(e -> assertEquals(e.getValue(), compact.get(e.getKey())));
  }
}
