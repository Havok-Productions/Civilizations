package dev.hearth.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal OpenAI-compatible {@code /chat/completions} client, shared by all
 * Quen agents.
 *
 * <p>Works against the local {@code llama-server} bundled by Hearth (see
 * {@link LocalAIManager}) and against any remote OpenAI-compatible endpoint
 * (DeepSeek, OpenAI, LM Studio, ...).
 *
 * <p>Thread-safe and Bukkit-free: intended to be called from an agent's own
 * executor thread, which keeps all LLM I/O off the Folia region threads.
 */
public final class ChatCompletions {

    private static final Gson GSON = new Gson();

    private ChatCompletions() {
    }

    /**
     * Run one system+user chat completion.
     *
     * @param client         shared HTTP client
     * @param baseUrl        e.g. {@code http://127.0.0.1:8642/v1} or a remote API base
     * @param apiKey         bearer token (ignored by llama-server, pass anything non-empty)
     * @param model          model name / id
     * @param temperature    sampling temperature
     * @param maxTokens      max tokens in the reply
     * @param timeoutSeconds request timeout
     * @param system         system prompt
     * @param user           user prompt
     * @return future holding the assistant's message content; completes
     *         exceptionally on HTTP/parse failure
     */
    public static CompletableFuture<String> call(HttpClient client, String baseUrl, String apiKey,
                                                 String model, double temperature, int maxTokens,
                                                 int timeoutSeconds, String system, String user) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", maxTokens);
        JsonArray messages = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", system);
        messages.add(sys);
        JsonObject usr = new JsonObject();
        usr.addProperty("role", "user");
        usr.addProperty("content", user);
        messages.add(usr);
        body.add("messages", messages);

        String url = (baseUrl == null || baseUrl.isBlank() ? "" : baseUrl.replaceAll("/$", "")) + "/chat/completions";
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(ChatCompletions::extractContent);
    }

    private static String extractContent(HttpResponse<String> resp) {
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 300));
        }
        try {
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            com.google.gson.JsonElement contentEl = json.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content");
            if (contentEl == null || contentEl.isJsonNull()) {
                throw new IllegalStateException("empty assistant message");
            }
            return contentEl.getAsString();
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("bad chat/completions response: " + ex.getMessage(), ex);
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
