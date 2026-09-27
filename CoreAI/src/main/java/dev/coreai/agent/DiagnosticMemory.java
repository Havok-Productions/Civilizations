package dev.coreai.agent;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Durable causal notes. Hosts supply evidence; notes never attest task completion. */
public final class DiagnosticMemory {
  public record Note(
      String id,
      String scope,
      String kind,
      String subject,
      String reason,
      String nextAction,
      String evidence,
      long occurrences,
      long updated) {}

  private final Path file;
  private final Map<String, Note> notes = new java.util.concurrent.ConcurrentHashMap<>();
  private final Gson json = new Gson();

  public DiagnosticMemory(Path directory) throws IOException {
    Files.createDirectories(directory);
    file = directory.resolve("diagnoses.json");
    if (Files.exists(file)) {
      try {
        Note[] saved = json.fromJson(Files.readString(file), Note[].class);
        if (saved == null) throw new IllegalArgumentException("Missing diagnostic notes");
        for (Note note : saved) {
          if (note.id() == null || note.scope() == null || note.kind() == null)
            throw new IllegalArgumentException("Incomplete diagnostic note");
          notes.put(note.id(), note);
        }
      } catch (RuntimeException error) {
        throw new IOException("Invalid diagnostic memory: " + file.getFileName(), error);
      }
    }
  }

  /** Call on the host IO executor, never on a world tick. */
  public Note remember(
      String scope,
      String kind,
      String subject,
      String reason,
      String nextAction,
      String evidence,
      long now)
      throws IOException {
    String id = id(scope + "\n" + kind + "\n" + subject);
    Note old = notes.get(id);
    Note note =
        new Note(
            id,
            scope,
            kind,
            subject,
            reason,
            nextAction,
            evidence,
            old == null ? 1 : old.occurrences() + 1,
            now);
    notes.put(id, note);
    flush();
    return note;
  }

  public List<Note> recent(String scope, int count) {
    return notes.values().stream()
        .filter(n -> n.scope().equals(scope))
        .sorted(Comparator.comparingLong(Note::updated).reversed())
        .limit(count)
        .toList();
  }

  public synchronized void flush() throws IOException {
    Path temporary = file.resolveSibling("diagnoses.tmp");
    Files.writeString(temporary, json.toJson(notes.values()));
    try {
      Files.move(
          temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  public static String id(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
