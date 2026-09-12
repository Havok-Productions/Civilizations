package dev.hearth.ai;

import dev.hearth.village.Village;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * Builds the JSON "village report" that is sent to the LLM advisor.
 * The report is intentionally small and factual so the model can reason about priorities.
 */
public final class ReportBuilder {

    private static final Gson GSON = new Gson();

    private ReportBuilder() {
    }

    public static String build(Village v, long worldTime) {
        JsonObject root = new JsonObject();
        root.addProperty("village", v.getName());
        root.addProperty("villagers", v.getVillagers().size());
        root.addProperty("beds", v.getBeds().size());

        String phase;
        if (worldTime >= 13000 || worldTime < 7000) {
            phase = "night";
        } else if (worldTime >= 6000 && worldTime < 12000) {
            phase = "morning";
        } else if (worldTime >= 12000 && worldTime < 18000) {
            phase = "afternoon";
        } else {
            phase = "evening";
        }
        root.addProperty("time_of_day", phase);

        JsonObject wall = new JsonObject();
        wall.addProperty("planned", v.getWallJobs().size() > 0);
        wall.addProperty("integrity", Math.round(v.getWallIntegrity() * 1000) / 10.0);
        wall.addProperty("built_blocks", v.getWallBuilt());
        wall.addProperty("needed_blocks", Math.max(0, v.getWallJobs().size() - v.getWallBuilt()));
        root.add("wall", wall);

        JsonObject chest = new JsonObject();
        chest.addProperty("exists", v.getChestLocation() != null);
        if (v.getChestLocation() != null) {
            JsonObject contents = new JsonObject();
            dev.hearth.HearthPlugin p = dev.hearth.HearthPlugin.get();
            if (p != null) {
                for (var entry : v.getChestContentsSummary().entrySet()) {
                    contents.addProperty(entry.getKey(), entry.getValue());
                }
            }
            chest.add("contents", contents);
        }
        root.add("chest", chest);

        JsonObject mine = new JsonObject();
        if (v.getMine() != null) {
            mine.addProperty("exists", true);
            mine.addProperty("progress_percent", v.getMine().getProgressPercent());
            mine.addProperty("completed", v.getMine().completed);
        } else {
            mine.addProperty("exists", false);
        }
        root.add("mine", mine);

        root.addProperty("monsters_nearby", v.getNearbyMonsters());
        root.addProperty("current_task", v.getCurrentTask() == null ? "none" : v.getCurrentTask().name().toLowerCase(Locale.ROOT));

        return GSON.toJson(root);
    }
}
