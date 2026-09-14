package dev.civilizations.ai;

import java.io.IOException;
import java.nio.file.*;
import java.util.function.Consumer;

/** Each launch owns its log; a previous process's Windows file handle is never rotated. */
final class RuntimeLogs {
  private RuntimeLogs() {}

  static ProcessBuilder.Redirect output(Path root, Consumer<String> log) {
    try {
      Path directory = root.resolve("logs");
      Files.createDirectories(directory);
      Path file = Files.createTempFile(directory, "runtime-", ".log");
      log.accept("runtime log: " + file.toAbsolutePath());
      return ProcessBuilder.Redirect.appendTo(file.toFile());
    } catch (IOException | SecurityException e) {
      log.accept(
          "runtime file logging unavailable: "
              + e.getMessage()
              + "; continuing with engine output discarded");
      return ProcessBuilder.Redirect.DISCARD;
    }
  }
}
