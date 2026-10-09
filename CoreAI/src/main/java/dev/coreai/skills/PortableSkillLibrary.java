package dev.coreai.skills;

import com.google.gson.Gson;
import dev.coreai.SkillProgram;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Host-verified relative programs. All access belongs to the host's learning IO thread. */
public final class PortableSkillLibrary {
  public record Entry(
      String id,
      SkillSituation.Descriptor situation,
      String source,
      String teacher,
      String verifiedEvidence,
      String lastEvidence,
      int successes,
      int failures,
      long firstVerified,
      long updated) {
    public SkillProgram program() {
      return SkillProgram.parse(source);
    }

    public boolean suspended() {
      return failures > 0;
    }
  }

  private record State(int schema, List<Entry> entries) {}

  private static final Gson JSON = new Gson();
  private final Path file;
  private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

  public PortableSkillLibrary(Path folder) throws IOException {
    Files.createDirectories(folder);
    file = folder.resolve("portable-skills.json");
    if (!Files.exists(file)) return;
    if (Files.size(file) > 4_000_000) throw new IOException("Portable skill state size limit");
    try {
      State state = JSON.fromJson(Files.readString(file), State.class);
      if (state == null
          || state.schema() != 1
          || state.entries() == null
          || state.entries().size() > 128)
        throw new IllegalArgumentException("Invalid portable skill schema");
      for (Entry entry : state.entries()) {
        validate(entry);
        if (entries.putIfAbsent(entry.id(), entry) != null)
          throw new IllegalArgumentException("Duplicate portable skill identity");
      }
    } catch (RuntimeException error) {
      throw new IOException("Invalid portable skill state", error);
    }
  }

  public Entry reusable(SkillSituation situation) {
    Objects.requireNonNull(situation);
    return entries.values().stream()
        .filter(entry -> !entry.suspended() && entry.situation().equals(situation.descriptor()))
        .filter(entry -> situation.observedFor(entry.program()))
        .max(Comparator.comparingInt(Entry::successes).thenComparingLong(Entry::updated))
        .orElse(null);
  }

  /** Also suppresses legacy location-specific copies of a failed translated program. */
  public boolean failedBefore(SkillSituation situation, SkillProgram program) {
    Entry entry = entries.get(identity(situation.descriptor(), program));
    return entry != null && entry.suspended();
  }

  public Entry previousFailure(SkillSituation situation) {
    return entries.values().stream()
        .filter(entry -> entry.suspended() && entry.situation().equals(situation.descriptor()))
        .max(Comparator.comparingLong(Entry::updated))
        .orElse(null);
  }

  /** Call only after the physical host verifies the original goal, never from model assertions. */
  public Entry verifiedSuccess(
      SkillSituation situation, SkillProgram program, String teacher, String evidence, long now)
      throws IOException {
    Objects.requireNonNull(situation);
    // Round-trip validation also protects callers constructing the public record directly.
    program = SkillProgram.parse(Objects.requireNonNull(program).source());
    if (!situation.observedFor(program))
      throw new IllegalArgumentException("Portable skill contains unobserved targets");
    requireText(teacher, "teacher provenance");
    requireText(evidence, "host evidence");
    String id = identity(situation.descriptor(), program);
    Entry old = entries.get(id);
    Entry entry =
        new Entry(
            id,
            situation.descriptor(),
            old == null ? program.source() : old.source(),
            old == null ? teacher : old.teacher(),
            old == null ? evidence : old.verifiedEvidence(),
            evidence,
            old == null ? 1 : Math.addExact(old.successes(), 1),
            old == null ? 0 : old.failures(),
            old == null ? now : old.firstVerified(),
            now);
    commit(entry);
    return entry;
  }

  /** A failure is attributed to the matched pre-action situation, not the mutated terrain. */
  public boolean failedReplay(
      SkillSituation situation, SkillProgram program, String evidence, long now)
      throws IOException {
    return failedReplay(identity(situation.descriptor(), program), situation, evidence, now);
  }

  public boolean failedReplay(String id, SkillSituation situation, String evidence, long now)
      throws IOException {
    Entry old = entries.get(id);
    if (old == null || !old.situation().equals(situation.descriptor())) return false;
    requireText(evidence, "host failure evidence");
    Entry failed =
        new Entry(
            old.id(),
            old.situation(),
            old.source(),
            old.teacher(),
            old.verifiedEvidence(),
            evidence,
            old.successes(),
            Math.addExact(old.failures(), 1),
            old.firstVerified(),
            now);
    try {
      commit(failed);
    } catch (IOException error) {
      // A disk error must not make this process immediately replay a known failed skill.
      entries.put(failed.id(), failed);
      throw error;
    }
    return true;
  }

  private static String identity(SkillSituation.Descriptor situation, SkillProgram program) {
    return SkillSituation.digest(JSON.toJson(situation) + "\n" + JSON.toJson(program.steps()));
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + field);
  }

  private static void validate(Entry entry) {
    Objects.requireNonNull(entry);
    Objects.requireNonNull(entry.situation());
    requireText(entry.teacher(), "teacher provenance");
    requireText(entry.verifiedEvidence(), "verified host evidence");
    requireText(entry.lastEvidence(), "host evidence");
    if (entry.successes() < 1
        || entry.failures() < 0
        || !identity(entry.situation(), entry.program()).equals(entry.id()))
      throw new IllegalArgumentException("Invalid portable skill evidence or identity");
  }

  private void commit(Entry entry) throws IOException {
    validate(entry);
    var next = new LinkedHashMap<>(entries);
    next.remove(entry.id());
    next.put(entry.id(), entry);
    while (next.size() > 128) next.remove(next.keySet().iterator().next());
    String encoded = JSON.toJson(new State(1, List.copyOf(next.values())));
    if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4_000_000)
      throw new IOException("Portable skill state size limit");
    Path temporary = file.resolveSibling("portable-skills.tmp");
    Files.writeString(temporary, encoded);
    try {
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException error) {
      Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
    entries.clear();
    entries.putAll(next);
  }
}
