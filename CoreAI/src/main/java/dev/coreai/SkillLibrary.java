package dev.coreai;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Context-specific live evidence, never inferred success from a model or replay. IO-thread only.
 */
public final class SkillLibrary {
  public record Experience(
      String context,
      String source,
      String teacher,
      int successes,
      int failures,
      String status,
      String evidence,
      long time) {}

  private final Path file;
  private final LinkedHashMap<String, Experience> records = new LinkedHashMap<>();

  public SkillLibrary(Path root) throws IOException {
    Files.createDirectories(root);
    file = root.resolve("skills.json");
    if (Files.exists(file)) {
      if (Files.size(file) > 2_000_000) throw new IOException("Skill state size limit");
      try {
        Experience[] values = new Gson().fromJson(Files.readString(file), Experience[].class);
        if (values == null || values.length > 128)
          throw new IllegalArgumentException("Skill count");
        for (Experience e : values) {
          SkillProgram.parse(e.source());
          records.put(entryKey(e), e);
        }
      } catch (RuntimeException e) {
        throw new IOException("Invalid skill state", e);
      }
    }
  }

  public Experience get(String context) {
    Experience last = null;
    for (Experience e : records.values()) if (e.context().equals(context)) last = e;
    return last;
  }

  private static String entryKey(Experience e) {
    return e.context() + ":" + SkillProgram.parse(e.source()).steps().toString();
  }

  public SkillProgram reusable(String context) {
    Experience e =
        records.values().stream()
            .filter(v -> v.context().equals(context) && v.successes() > 0 && v.failures() == 0)
            .max(Comparator.comparingInt(Experience::successes).thenComparingLong(Experience::time))
            .orElse(null);
    return e != null && e.successes() > 0 && e.failures() == 0
        ? SkillProgram.parse(e.source())
        : null;
  }

  public boolean repeatedFailure(String context, SkillProgram candidate) {
    return records.values().stream()
        .anyMatch(
            e ->
                e.context().equals(context)
                    && e.failures() > 0
                    && sameInstructions(e.source(), candidate.source()));
  }

  private static boolean sameInstructions(String a, String b) {
    return SkillProgram.parse(a).steps().equals(SkillProgram.parse(b).steps());
  }

  public Experience outcome(
      String context,
      SkillProgram program,
      String teacher,
      boolean success,
      String evidence,
      long now)
      throws IOException {
    Experience previous = records.get(context + ":" + program.steps().toString());
    boolean same = previous != null && sameInstructions(previous.source(), program.source());
    int successes = (same ? previous.successes() : 0) + (success ? 1 : 0),
        failures = (same ? previous.failures() : 0) + (success ? 0 : 1);
    Experience entry =
        new Experience(
            context,
            program.source(),
            teacher,
            successes,
            failures,
            !success ? "suspended" : successes >= 3 ? "adopted_for_context" : "verified_once",
            evidence.substring(0, Math.min(12000, evidence.length())),
            now);
    var next = new LinkedHashMap<>(records);
    String key = entryKey(entry);
    next.remove(key);
    next.put(key, entry);
    while (next.size() > 128) next.remove(next.keySet().iterator().next());
    Path temporary = file.resolveSibling("skills.tmp");
    String encoded = new Gson().toJson(next.values());
    while (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_900_000
        && next.size() > 1) {
      next.remove(next.keySet().iterator().next());
      encoded = new Gson().toJson(next.values());
    }
    Files.writeString(temporary, encoded);
    try {
      Files.move(
          temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
    records.clear();
    records.putAll(next);
    return entry;
  }
}
