package dev.hearth.ai;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hearth.HearthPlugin;
import dev.hearth.brain.TaskType;
import dev.hearth.village.Village;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * LLM advisor that speaks the OpenAI-compatible /chat/completions API.
 * Works with both OpenAI (ChatGPT) and DeepSeek by changing base-url + model in config.
 *
 * <p>The HTTP call is made on a background thread (java.net.http client is thread-safe);
 * the result is marshalled back onto the <em>village center's region thread</em>
 * (via {@code HearthPlugin#runInRegion}) before the callback may touch
 * village state — Folia-safe, since no legacy global scheduler task is used.
 */
public class LLMAdvisor implements AIAdvisor {

    private static final Gson GSON = new Gson();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final HearthPlugin plugin;

    public LLMAdvisor(HearthPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Re-read config after /hearth reload.
     */
    public void refresh() {
        // Config is read live from plugin getters, so nothing to do here.
        // Kept for API symmetry.
    }

    @Override
    public void advise(HearthPlugin plugin, Village village, Consumer<AIAdvice> callback) {
        if (plugin.aiApiKey() == null || plugin.aiApiKey().isBlank() || plugin.aiApiKey().startsWith("sk-put-")) {
            callback.accept(null);
            return;
        }
        String report = ReportBuilder.build(village, village.getWorld().getTime());
        String system = "You are Hearth, the strategic advisor for a Minecraft villager village.\n"
                + "Village charter:\n" + plugin.aiCharter() + "\n"
                + "Given the JSON village report, pick the single most important next task.\n"
                + "Respond ONLY with minified JSON, no markdown, in this exact shape:\n"
                + "{\"task\":\"sleep|repair_wall|build_wall|place_chest|lighting|mine|gather|idle\","
                + "\"reason\":\"one short sentence\"}";
        String user = "Village report:\n" + report;

        JsonObject body = new JsonObject();
        body.addProperty("model", plugin.aiModel());
        body.addProperty("temperature", plugin.aiTemperature());
        body.addProperty("max_tokens", plugin.aiMaxTokens());
        com.google.gson.JsonArray messageArray = new com.google.gson.JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", system);
        messageArray.add(sys);
        JsonObject usr = new JsonObject();
        usr.addProperty("role", "user");
        usr.addProperty("content", user);
        messageArray.add(usr);
        body.add("messages", messageArray);

        String url = plugin.aiBaseUrl().replaceAll("/$", "") + "/chat/completions";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(5, plugin.aiTimeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + plugin.aiApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApplyAsync(resp -> parse(resp, plugin, village))
                    .thenAccept(advice -> plugin.runInRegion(village.getCenter(), () -> callback.accept(advice)))
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("AI advisor request failed: " + ex.getMessage());
                        plugin.runInRegion(village.getCenter(), () -> callback.accept(null));
                        return null;
                    });
        } catch (Exception ex) {
            plugin.getLogger().warning("AI advisor request failed: " + ex.getMessage());
            callback.accept(null);
        }
    }

    private AIAdvice parse(HttpResponse<String> resp, HearthPlugin plugin, Village village) {
        if (resp.statusCode() / 100 != 2) {
            plugin.getLogger().warning("AI advisor HTTP " + resp.statusCode() + ": " + resp.body());
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonElement contentEl = json.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content");
            if (contentEl == null || contentEl.isJsonNull()) {
                return null;
            }
            String content = contentEl.getAsString();
            // Models sometimes wrap JSON in code fences; strip them.
            String cleaned = content.trim();
            if (cleaned.startsWith("```")) {
                int firstNewline = cleaned.indexOf('\n');
                int lastFence = cleaned.lastIndexOf("```");
                if (firstNewline > 0 && lastFence > firstNewline) {
                    cleaned = cleaned.substring(firstNewline + 1, lastFence).trim();
                }
            }
            JsonObject advice = JsonParser.parseString(cleaned).getAsJsonObject();
            String taskName = advice.get("task") == null ? "idle" : advice.get("task").getAsString().toLowerCase(Locale.ROOT);
            String reason = advice.get("reason") == null ? "" : advice.get("reason").getAsString();
            TaskType task = mapTask(taskName);
            return new AIAdvice(task, reason, System.currentTimeMillis());
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not parse AI advice: " + ex.getMessage());
            return null;
        }
    }

    private TaskType mapTask(String name) {
        return switch (name) {
            case "sleep" -> TaskType.SLEEP;
            case "repair_wall", "repair" -> TaskType.REPAIR;
            case "build_wall", "wall", "build" -> TaskType.WALL;
            case "place_chest", "chest" -> TaskType.CHEST;
            case "lighting", "light" -> TaskType.LIGHT;
            case "mine", "mining", "dig" -> TaskType.MINE;
            case "gather", "collect", "resources" -> TaskType.GATHER;
            default -> TaskType.IDLE;
        };
    }
}
