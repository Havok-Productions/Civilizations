package dev.coreai;

import com.google.gson.Gson;
import java.io.*;
import java.nio.file.*;
import java.util.Map;

/** Host-owned bounded storage. Call only from the adapter's bounded IO executor. */
public final class DataJournal {
  private static final Gson JSON = new Gson();
  private final Path root;

  public DataJournal(Path root) throws IOException {
    this.root = root.toAbsolutePath().normalize();
    Files.createDirectories(this.root);
  }

  public synchronized void append(String stream, Map<String, ?> event) throws IOException {
    if (!java.util.Set.of("observations", "outcomes", "proposals", "roadblocks", "experiments")
        .contains(stream)) throw new IllegalArgumentException("Unknown stream");
    String line = JSON.toJson(event);
    if (line.length() > 64_000) throw new IOException("Event exceeds 64K limit");
    Path file = root.resolve(stream + ".jsonl");
    if (Files.exists(file) && Files.size(file) >= 8L * 1024 * 1024) {
      for (int i = 3; i >= 1; i--) {
        Path from = root.resolve(stream + (i == 1 ? "" : "." + (i - 1)) + ".jsonl");
        if (Files.exists(from))
          Files.move(
              from, root.resolve(stream + "." + i + ".jsonl"), StandardCopyOption.REPLACE_EXISTING);
      }
    }
    Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }
}
