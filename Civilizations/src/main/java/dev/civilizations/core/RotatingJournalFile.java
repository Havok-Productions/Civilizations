package dev.civilizations.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Single-writer retry state: completed rotation moves and partial appends are not repeated. */
final class RotatingJournalFile {
  @FunctionalInterface
  interface Mover {
    void move(Path from, Path to) throws IOException;
  }

  private final Path directory, file;
  private final long maxBytes;
  private final Mover mover;
  private int rotation = -1;
  private long appendOffset = -1;

  RotatingJournalFile(Path directory, long maxBytes) {
    this(
        directory,
        maxBytes,
        (from, to) -> Files.move(from, to, StandardCopyOption.REPLACE_EXISTING));
  }

  RotatingJournalFile(Path directory, long maxBytes, Mover mover) {
    this.directory = directory;
    this.file = directory.resolve("events.jsonl");
    this.maxBytes = maxBytes;
    this.mover = mover;
  }

  void append(byte[] bytes) throws IOException {
    Files.createDirectories(directory);
    if (appendOffset < 0
        && rotation < 0
        && Files.exists(file)
        && Files.size(file) + bytes.length > maxBytes) rotation = 0;
    while (rotation >= 0) {
      if (rotation == 0) Files.deleteIfExists(directory.resolve("events.4.jsonl"));
      else {
        int source = 4 - rotation;
        Path from = source == 0 ? file : directory.resolve("events." + source + ".jsonl");
        Path to = directory.resolve("events." + (source + 1) + ".jsonl");
        if (Files.exists(from)) mover.move(from, to);
      }
      rotation = rotation == 4 ? -1 : rotation + 1;
    }
    try (var channel =
        FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      if (appendOffset < 0) appendOffset = channel.size();
      channel.position(appendOffset);
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) channel.write(buffer);
      // Retrying an incomplete write starts at the original offset, never in a partial JSON line.
      channel.truncate(appendOffset + bytes.length);
    }
    appendOffset = -1;
  }
}
