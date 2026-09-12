package dev.hearth.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hearth.brain.TaskType;

import java.util.Locale;

/**
 * Parses a Quen agent's reply into an {@link AIAdvice}.
 *
 * <p>Tolerant of real-world model output: code fences, leading/trailing prose
 * around the JSON, and loose task names.
 */
public final class AdviceParser {

    private AdviceParser() {
    }

    /**
     * @param content raw assistant message (may be null)
     * @return parsed advice, or null when the reply is unusable
     */
    public static AIAdvice parse(String content) {
        if (content == null) {
            return null;
        }
        String cleaned = content.trim();
        // Models sometimes wrap the JSON in a code fence; strip it.
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).trim();
            }
        }
        // Some models still add prose; keep only the outermost {...} object.
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            cleaned = cleaned.substring(start, end + 1);
        }
        JsonObject advice;
        try {
            advice = JsonParser.parseString(cleaned).getAsJsonObject();
        } catch (Exception ex) {
            return null;
        }
        String taskName = advice.get("task") == null ? "idle" : advice.get("task").getAsString().toLowerCase(Locale.ROOT);
        String reason = advice.get("reason") == null ? "" : advice.get("reason").getAsString();
        return new AIAdvice(mapTask(taskName), reason, System.currentTimeMillis());
    }

    /**
     * Map loose model output to a concrete task. Unknown values fall back to
     * IDLE (the safe choice).
     */
    public static TaskType mapTask(String name) {
        if (name == null) {
            return TaskType.IDLE;
        }
        return switch (name.trim()) {
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
