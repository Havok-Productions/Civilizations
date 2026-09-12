package dev.hearth.social;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The village's shared ledger — the book on the bookshelf.
 *
 * <p>Every village gets one persistent, human-readable JSON file:
 * {@code plugins/Civilizations/books/<villageId>.json}. Entries are one of
 * three kinds:
 * <ul>
 *   <li>{@code plan} — what the village decided to focus on (written by the
 *       village coordination tick when its task changes);</li>
 *   <li>{@code need} — a request any villager can pick up, formatted as
 *       {@code material:<MATERIAL>} (e.g. {@code material:STONE}); needs are
 *       <em>claimable</em>: the first villager that claims one works on it,
 *       and stale claims release automatically;</li>
 *   <li>{@code obs} — observations ("deposited 12 stone into the chest",
 *       "first block of the mine tunnel dug") so the Quen agents — and any
 *       admin reading the file — can see what the village actually did.</li>
 * </ul>
 *
 * <p>The entry list is capped (oldest dropped), writes are atomic
 * (temp file + rename), and every public method is synchronized, so any
 * number of villager region threads can read or append concurrently.
 * File I/O here is plain NIO — no Bukkit world/entity access — which is
 * exactly what makes it Folia-safe from brain threads.
 */
public class VillageBook {

    public enum Kind { PLAN, NEED, OBS }

    /**
     * One line of the book. {@code claimedBy}/{@code claimedAt} are only used
     * for NEED entries; they are volatile because claims are written by the
     * claiming villager's region thread and read by everyone else's.
     */
    public static final class Entry {
        public final long t;
        public final String author;
        public final Kind kind;
        public final String text;
        public volatile UUID claimedBy;
        public volatile long claimedAt;

        Entry(long t, String author, Kind kind, String text) {
            this.t = t;
            this.author = author;
            this.kind = kind;
            this.text = text;
        }

        public boolean isClaimStale(long now, long ttlMs) {
            return claimedBy != null && claimedAt > 0 && now - claimedAt > ttlMs;
        }

        public boolean isClaimedBy(UUID who) {
            return claimedBy != null && claimedBy.equals(who);
        }

        public boolean isUnclaimedOrStale(long now, long ttlMs) {
            return claimedBy == null || now - claimedAt > ttlMs;
        }
    }

    private static final Gson GSON = new Gson();

    private final File dir;
    private final File file;
    private final int maxEntries;
    private final Object lock = new Object();
    private final List<Entry> entries = new ArrayList<>(); // newest last
    private String villageName = "";

    public VillageBook(File dir, UUID villageId, int maxEntries) {
        this.dir = dir;
        this.dir.mkdirs();
        this.file = new File(dir, villageId + ".json");
        this.maxEntries = Math.max(10, maxEntries);
        load();
    }

    public File file() {
        return file;
    }

    public String villageName() {
        return villageName;
    }

    public void setVillageName(String name) {
        synchronized (lock) {
            this.villageName = name == null ? "" : name;
        }
    }

    // ----------------------------------------------------------------
    // Reads
    // ----------------------------------------------------------------

    /**
     * The last {@code n} entries, oldest first. Returns a defensive copy.
     */
    public List<Entry> recent(int n) {
        synchronized (lock) {
            if (n <= 0 || entries.isEmpty()) {
                return List.of();
            }
            int from = Math.max(0, entries.size() - n);
            return new ArrayList<>(entries.subList(from, entries.size()));
        }
    }

    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    /**
     * The oldest unclaimed (or stale) NEED entry, or null when none exists.
     */
    public Entry firstUnclaimedNeed(long now, long staleTtlMs) {
        synchronized (lock) {
            for (Entry e : entries) {
                if (e.kind == Kind.NEED && e.isUnclaimedOrStale(now, staleTtlMs)) {
                    return e;
                }
            }
            return null;
        }
    }

    /**
     * A short human-readable digest of the most recent entries (for prompts
     * and status output).
     */
    public String recentText(int n) {
        synchronized (lock) {
            if (entries.isEmpty()) {
                return "(the book is empty)";
            }
            int from = Math.max(0, entries.size() - n);
            List<String> lines = new ArrayList<>();
            for (int i = from; i < entries.size(); i++) {
                Entry e = entries.get(i);
                lines.add("- [" + e.kind.name().toLowerCase(Locale.ROOT) + "] "
                        + e.author + ": " + e.text
                        + (e.claimedBy != null ? " (claimed by " + shortId(e.claimedBy) + ")" : ""));
            }
            return String.join("\n", lines);
        }
    }

    // ----------------------------------------------------------------
    // Writes
    // ----------------------------------------------------------------

    /**
     * Append a line and persist. Never throws; a failed write is best-effort
     * (the entry still exists in memory for this JVM run).
     */
    public void append(Kind kind, String author, String text) {
        if (kind == null || text == null || text.isBlank()) {
            return;
        }
        synchronized (lock) {
            entries.add(new Entry(System.currentTimeMillis(), author, kind, text));
            while (entries.size() > maxEntries) {
                entries.remove(0);
            }
            persist();
        }
    }

    /**
     * Claim a need for this villager. Atomic against other claimants.
     *
     * @return true if this villager now owns the need
     */
    public boolean claim(Entry entry, UUID who, long now) {
        synchronized (lock) {
            if (entry.kind != Kind.NEED || !entry.isUnclaimedOrStale(now, Long.MAX_VALUE)) {
                return false;
            }
            entry.claimedBy = who;
            entry.claimedAt = now;
            persist();
            return true;
        }
    }

    /**
     * Release a need back to the pool (the picker gave up on it).
     */
    public void release(Entry entry, UUID who) {
        synchronized (lock) {
            if (entry.claimedBy != null && entry.claimedBy.equals(who)) {
                entry.claimedBy = null;
                entry.claimedAt = 0;
                persist();
            }
        }
    }

    /**
     * Drop stale claims so a dead/stuck picker's needs become claimable again.
     */
    public int releaseStale(long now, long ttlMs) {
        int freed = 0;
        synchronized (lock) {
            for (Entry e : entries) {
                if (e.kind == Kind.NEED && e.isClaimStale(now, ttlMs)) {
                    e.claimedBy = null;
                    e.claimedAt = 0;
                    freed++;
                }
            }
            if (freed > 0) {
                persist();
            }
        }
        return freed;
    }

    // ----------------------------------------------------------------
    // Need text helpers (kept Bukkit-free on purpose)
    // ----------------------------------------------------------------

    public static String formatMaterialNeed(String materialName) {
        return "material:" + materialName.toUpperCase(Locale.ROOT);
    }

    /**
     * Parse a need's text back into a material name, or null if it is not a
     * material need.
     */
    public static String parseNeedMaterial(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (!t.toLowerCase(Locale.ROOT).startsWith("material:")) {
            return null;
        }
        String name = t.substring("material:".length()).trim();
        return name.isEmpty() ? null : name.toUpperCase(Locale.ROOT);
    }

    // ----------------------------------------------------------------
    // Persistence
    // ----------------------------------------------------------------

    private void load() {
        synchronized (lock) {
            if (!file.exists() || file.length() == 0) {
                return;
            }
            try {
                String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                JsonObject root = GSON.fromJson(text, JsonObject.class);
                if (root == null) {
                    return;
                }
                if (root.has("village")) {
                    villageName = root.get("village").getAsString();
                }
                if (root.has("entries") && root.get("entries").isJsonArray()) {
                    for (var el : root.getAsJsonArray("entries")) {
                        if (!el.isJsonObject()) {
                            continue;
                        }
                        JsonObject o = el.getAsJsonObject();
                        Kind kind = parseKind(o.get("kind") == null ? "obs" : o.get("kind").getAsString());
                        long t = o.has("t") ? o.get("t").getAsLong() : 0L;
                        String author = o.has("author") ? o.get("author").getAsString() : "";
                        String txt = o.has("text") ? o.get("text").getAsString() : "";
                        Entry e = new Entry(t, author, kind, txt);
                        if (o.has("claimedBy")) {
                            try {
                                e.claimedBy = UUID.fromString(o.get("claimedBy").getAsString());
                            } catch (IllegalArgumentException ignored) {
                                e.claimedBy = null;
                            }
                            e.claimedAt = o.has("claimedAt") ? o.get("claimedAt").getAsLong() : 0L;
                        }
                        entries.add(e);
                    }
                    while (entries.size() > maxEntries) {
                        entries.remove(0);
                    }
                }
            } catch (Exception ignored) {
                // Corrupt book: start fresh rather than break the village.
                entries.clear();
            }
        }
    }

    private void persist() {
        // Caller must hold `lock`.
        JsonObject root = new JsonObject();
        root.addProperty("village", villageName);
        root.addProperty("updated", System.currentTimeMillis());
        JsonArray arr = new JsonArray();
        for (Entry e : entries) {
            JsonObject o = new JsonObject();
            o.addProperty("t", e.t);
            o.addProperty("author", e.author);
            o.addProperty("kind", e.kind.name().toLowerCase(Locale.ROOT));
            o.addProperty("text", e.text);
            if (e.claimedBy != null) {
                o.addProperty("claimedBy", e.claimedBy.toString());
                o.addProperty("claimedAt", e.claimedAt);
            }
            arr.add(o);
        }
        root.add("entries", arr);
        File tmp = new File(dir, file.getName() + ".tmp");
        try {
            Files.writeString(tmp.toPath(), GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            deleteQuietly(tmp);
        }
    }

    private static Kind parseKind(String s) {
        try {
            return Kind.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Kind.OBS;
        }
    }

    private static String shortId(UUID id) {
        return id == null ? "?" : id.toString().substring(0, 8);
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists() && !f.delete()) {
            f.deleteOnExit();
        }
    }
}
