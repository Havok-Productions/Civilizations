package dev.civilizations.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Bounded, asynchronous JSONL journal. Never performs disk I/O on a region thread. */
public final class DebugJournal implements AutoCloseable {
  private final ArrayBlockingQueue<Map<String, Object>> queue = new ArrayBlockingQueue<>(2048);
  private final AtomicLong dropped = new AtomicLong();
  private final Path directory;
  private final long maxBytes;
  private final Consumer<String> warning;
  private volatile boolean closing;
  private final Thread writer;

  public DebugJournal(Path directory, long maxBytes, Consumer<String> warning) {
    this.directory = directory;
    this.maxBytes = maxBytes;
    this.warning = warning;
    writer = new Thread(this::writeLoop, "Civilizations-debug");
    writer.setDaemon(true);
    writer.start();
  }

  public void event(String village, String worker, String type, Map<String, ?> data) {
    if (closing) return;
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("time", Instant.now().toString());
    event.put("village", village);
    event.put("worker", worker);
    event.put("type", type);
    // A recovery can fail before an instruction exists. Optional evidence must not abort it.
    event.put("data", Collections.unmodifiableMap(new LinkedHashMap<>(data)));
    if (!queue.offer(event)) dropped.incrementAndGet();
  }

  private void writeLoop() {
    Gson gson = new GsonBuilder().serializeNulls().create();
    try {
      Files.createDirectories(directory);
      Path file = directory.resolve("events.jsonl");
      while (!closing || !queue.isEmpty()) {
        Map<String, Object> next = queue.poll(250, TimeUnit.MILLISECONDS);
        if (next == null) continue;
        long lost = dropped.getAndSet(0);
        if (lost > 0) next.put("dropped_since_previous", lost);
        byte[] bytes = (gson.toJson(next) + "\n").getBytes(StandardCharsets.UTF_8);
        if (Files.exists(file) && Files.size(file) + bytes.length > maxBytes) {
          Files.deleteIfExists(directory.resolve("events.4.jsonl"));
          for (int i = 3; i >= 1; i--) {
            Path old = directory.resolve("events." + i + ".jsonl");
            if (Files.exists(old))
              Files.move(
                  old,
                  directory.resolve("events." + (i + 1) + ".jsonl"),
                  StandardCopyOption.REPLACE_EXISTING);
          }
          Files.move(
              file, directory.resolve("events.1.jsonl"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      }
    } catch (Exception e) {
      warning.accept("Debug journal unavailable: " + e.getMessage());
    }
  }

  @Override
  public void close() {
    closing = true;
  }

  public boolean awaitClosed(long millis) throws InterruptedException {
    writer.join(millis);
    return !writer.isAlive();
  }
}
