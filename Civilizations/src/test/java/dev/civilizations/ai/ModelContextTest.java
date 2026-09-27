package dev.civilizations.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;

class ModelContextTest {
  @Test
  @Tag("inference")
  @Tag("coreai")
  @Tag("diagnostics")
  @Tag("interaction")
  void recoveryBudgetRemovesDuplicatedEvidenceButRetainsCurrentActionGeometryAndDiagnosis()
      throws Exception {
    JsonObject original =
        JsonParser.parseString(
                """
                {"levels":[{"relative_y":0,"rows":["P??s"]}],"goal_relative":{"x":2,"y":27,"z":0},
                 "inventory":{"DIRT":2},"requested_observation":{"focus":{"material":"CHEST","removal":"protected"}},
                 "failed_execution":{"reason":"cannot reach chest","failed_index":2,
                   "remaining_instructions":[{"op":"WALK","x":2},{"op":"VERIFY"}],"navigation_evidence":{"reason":"blocked"}},
                 "diagnostic_memory":{"recent":[{"kind":"request_capacity","next_action":"compact history"}]},
                 "tunable_parameters":{"navigation.transition_ms":{"current":1000,"definition":{"help":"test"}}}}
                """)
            .getAsJsonObject();
    original
        .getAsJsonObject("failed_execution")
        .getAsJsonObject("navigation_evidence")
        .addProperty("cells", "old voxel dump ".repeat(5000));
    original.add(
        "previous_program",
        JsonParser.parseString("{\"explanation\":\"old plan\",\"steps\":[{\"op\":\"WALK\"}]}"));
    var body =
        LocalRuntime.requestBody(
            LocalRuntime.Settings.read(new YamlConfiguration()),
            "system",
            ModelContext.compact(original.toString()),
            ReasoningMode.RECOVERY,
            "{}");
    var prepared =
        PromptBudget.prepare(
            body,
            4096,
            request -> {
              var user =
                  JsonParser.parseString(
                          request
                              .getAsJsonArray("messages")
                              .get(1)
                              .getAsJsonObject()
                              .get("content")
                              .getAsString())
                      .getAsJsonObject();
              return user.has("previous_program") ? 4000 : 1000;
            });
    var sent =
        JsonParser.parseString(
                prepared
                    .body()
                    .getAsJsonArray("messages")
                    .get(1)
                    .getAsJsonObject()
                    .get("content")
                    .getAsString())
            .getAsJsonObject();
    for (String key :
        List.of(
            "levels", "goal_relative", "inventory", "requested_observation", "diagnostic_memory"))
      assertEquals(original.get(key), sent.get(key), key);
    assertEquals(
        "WALK",
        sent.getAsJsonObject("failed_execution")
            .getAsJsonObject("instruction_at_revision")
            .get("op")
            .getAsString());
    assertTrue(sent.toString().contains("cannot reach chest"));
    assertFalse(sent.toString().contains("old voxel dump"));
    assertTrue(original.toString().contains("old voxel dump"));
    assertTrue(prepared.promptTokens() + prepared.outputTokens() + 64 <= 4096);
  }

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
