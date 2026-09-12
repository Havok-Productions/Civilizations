package dev.hearth.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.hearth.HearthPlugin;
import dev.hearth.village.Village;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persistent, human-readable memory for one Quen agent.
 *
 * <p>Layout: one JSON file per village inside the agent's own directory
 * ({@code plugins/Civilizations/memory/agent-<N>/<villageId>.json}):
 * <pre>
 * {
 *   "village": "name",
 *   "updated": 1700000000000,
 *   "adviceCounts": { "mine": 3, "wall": 1 },
 *   "lastSnapshot": { "wallIntegrity": 96.0, "minePercent": 40, "chestItems": 120 },
 *   "lastTask": "mine",
 *   "learnings": [ { "t": 1700000000000, "note": "mine progress 40% -> 45%" } ]
 * }
 * </pre>
 *
 * <p>The agent diffs consecutive snapshots to record what it is learning about
 * the village (progress deltas, task choices). The file is plain JSON on
 * purpose: a server admin can read or edit it, and the knowledge survives
 * restarts. Writes are atomic (temp file + rename) and synchronized.
 *
 * <p>Only read-only village getters are used, so this may be called from any
 * thread (in practice it runs on the owning agent's executor thread).
 */
public class AgentMemory {

    private static final Gson GSON = new Gson();
    private static final int MAX_LEARNINGS = 200;

    private final File dir;
    private final Object lock = new Object();

    public AgentMemory(File dir) {
        this.dir = dir;
        dir.mkdirs();
    }

    public File directory() {
        return dir;
    }

    /**
     * The most recent learnings, formatted for the prompt (newest last).
     */
    public String recentLearnings(Village village, int n) {
        synchronized (lock) {
            JsonObject data = read(village);
            if (data == null || data.get("learnings") == null) {
                return "";
            }
            JsonArray arr = data.getAsJsonArray("learnings");
            int from = Math.max(0, arr.size() - n);
            List<String> lines = new ArrayList<>();
            for (int i = from; i < arr.size(); i++) {
                JsonObject el = arr.get(i).getAsJsonObject();
                String note = el.get("note") == null ? "" : el.get("note").getAsString();
                if (!note.isBlank()) {
                    lines.add("- " + note);
                }
            }
            return String.join("\n", lines);
        }
    }

    /**
     * Record one advice round: update the counters, diff the village snapshot,
     * append the learnings, and atomically persist.
     */
    public void record(HearthPlugin plugin, Village village, AIAdvice advice) {
        synchronized (lock) {
            JsonObject data = read(village);
            if (data == null) {
                data = new JsonObject();
            }
            data.addProperty("village", village.getName());
            data.addProperty("updated", System.currentTimeMillis());

            // Advice counters.
            JsonObject counts = data.has("adviceCounts") && data.get("adviceCounts").isJsonObject()
                    ? data.getAsJsonObject("adviceCounts") : new JsonObject();
            if (advice != null) {
                String key = advice.task.name().toLowerCase(Locale.ROOT);
                counts.addProperty(key, counts.has(key) ? counts.get(key).getAsInt() + 1 : 1);
                data.add("adviceCounts", counts);
                data.addProperty("lastTask", key);
            }

            // Snapshot diff -> learnings.
            JsonObject prev = data.has("lastSnapshot") && data.get("lastSnapshot").isJsonObject()
                    ? data.getAsJsonObject("lastSnapshot") : null;
            int wall = (int) Math.round(village.getWallIntegrity() * 10.0);
            int mine = village.getMine() == null ? -1 : (village.getMine().completed ? 100 : village.getMine().getProgressPercent());
            int chest = 0;
            for (int amount : village.getChestContentsSummary().values()) {
                chest += amount;
            }
            JsonArray learnings = data.has("learnings") && data.get("learnings").isJsonArray()
                    ? data.getAsJsonArray("learnings") : new JsonArray();
            long now = System.currentTimeMillis();
            if (prev != null) {
                if (prev.has("minePercent") && prev.get("minePercent").getAsInt() != mine) {
                    learnings.add(note(now, "mine progress " + prev.get("minePercent").getAsInt() + "% -> " + mine + "%"));
                }
                if (prev.has("wallIntegrity") && prev.get("wallIntegrity").getAsInt() != wall) {
                    learnings.add(note(now, "wall integrity " + prev.get("wallIntegrity").getAsInt() + "% -> " + wall + "%"));
                }
                if (prev.has("chestItems") && prev.get("chestItems").getAsInt() != chest) {
                    learnings.add(note(now, "community chest items " + prev.get("chestItems").getAsInt() + " -> " + chest));
                }
            }
            if (advice != null && !advice.reason.isBlank()) {
                learnings.add(note(now, "chose " + advice.task.name().toLowerCase(Locale.ROOT) + ": " + advice.reason));
            }
            while (learnings.size() > MAX_LEARNINGS) {
                learnings.remove(0);
            }
            data.add("learnings", learnings);

            JsonObject snap = new JsonObject();
            snap.addProperty("wallIntegrity", wall);
            snap.addProperty("minePercent", mine);
            snap.addProperty("chestItems", chest);
            data.add("lastSnapshot", snap);

            write(village, data);
        }
    }

    // ---- persistence helpers ----

    private File fileFor(Village village) {
        return new File(dir, village.getId().toString() + ".json");
    }

    private JsonObject read(Village village) {
        File file = fileFor(village);
        if (!file.exists() || file.length() == 0) {
            return null;
        }
        try {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            return GSON.fromJson(text, JsonObject.class);
        } catch (Exception ex) {
            return null; // corrupt file: start fresh rather than crash the agent
        }
    }

    private void write(Village village, JsonObject data) {
        File file = fileFor(village);
        File tmp = new File(dir, village.getId() + ".json.tmp");
        try {
            dir.mkdirs();
            Files.writeString(tmp.toPath(), GSON.toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(tmp.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            // Memory is best-effort; a failed write must never break advice.
            deleteQuietly(tmp);
        }
    }

    private static JsonObject note(long t, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("t", t);
        o.addProperty("note", text);
        return o;
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists() && !f.delete()) {
            f.deleteOnExit();
        }
    }

    /**
     * All memory files of this agent (for diagnostics / /hearth ai status).
     */
    public List<String> villageFiles() {
        synchronized (lock) {
            Map<String, File> byName = new LinkedHashMap<>();
            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    byName.put(f.getName(), f);
                }
            }
            return new ArrayList<>(byName.keySet());
        }
    }
}
