package test;

import com.google.gson.*;
import dev.civilizations.ai.LocalRuntime;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/**
 * Explicit fixture-only call to an already-owned loopback model; never logs the token or reasoning.
 */
final class LocalRuleTeacher {
  static String complete(String system, String report, String schema, long deadline)
      throws Exception {
    URI uri = URI.create("http://127.0.0.1:8652/v1/chat/completions");
    Map<String, Object> body =
        Map.of(
            "model",
            "civilizations-deep",
            "temperature",
            0.2,
            "max_tokens",
            4096,
            "stream",
            false,
            "messages",
            List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", report)),
            "response_format",
            Map.of(
                "type",
                "json_schema",
                "json_schema",
                Map.of(
                    "name",
                    "villager_rule_trial",
                    "strict",
                    true,
                    "schema",
                    JsonParser.parseString(schema))));
    var request =
        HttpRequest.newBuilder(uri)
            .timeout(
                Duration.ofMillis(
                    Math.max(1000, Math.min(150000, deadline - System.currentTimeMillis()))))
            .header("Authorization", "Bearer " + System.getenv("CIV_TEST_MODEL_TOKEN"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(body)))
            .build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200)
      throw new IllegalStateException(
          "Local teacher HTTP "
              + response.statusCode()
              + ": "
              + response.body().substring(0, Math.min(500, response.body().length())));
    return LocalRuntime.finalText(response.body());
  }
}
