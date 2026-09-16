package dev.civilizations.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
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
  private volatile long closeDeadline = Long.MAX_VALUE;
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
    var file = new RotatingJournalFile(directory, maxBytes);
    byte[] pending = null;
    long nextWarning = 0;
    boolean recovering = false;
    while (pending != null || !closing || !queue.isEmpty()) {
      if (closing && recovering && System.nanoTime() >= closeDeadline) {
        warning.accept(
            "Debug journal closed with "
                + (queue.size() + (pending == null ? 0 : 1))
                + " unsaved events after repeated I/O failure");
        return;
      }
      try {
        if (pending == null) {
          Map<String, Object> next = queue.poll(250, TimeUnit.MILLISECONDS);
          if (next == null) continue;
          long lost = dropped.getAndSet(0);
          if (lost > 0) next.put("dropped_since_previous", lost);
          pending = (gson.toJson(next) + "\n").getBytes(StandardCharsets.UTF_8);
        }
        file.append(pending);
        pending = null;
        if (recovering) {
          warning.accept("Debug journal recovered; queued events are draining");
          recovering = false;
          nextWarning = 0;
        }
      } catch (IOException error) {
        recovering = true;
        long now = System.nanoTime();
        if (now >= nextWarning) {
          warning.accept(
              "Debug journal write deferred; event retained for retry: " + error.getMessage());
          nextWarning = now + TimeUnit.SECONDS.toNanos(10);
        }
        // This is the dedicated writer, never a Folia region thread.
        try {
          Thread.sleep(100);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException invalidEvent) {
        dropped.incrementAndGet();
        warning.accept("Debug event could not be serialized: " + invalidEvent.getMessage());
      }
    }
  }

  @Override
  public void close() {
    if (closing) return;
    closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    closing = true;
  }

  public boolean awaitClosed(long millis) throws InterruptedException {
    writer.join(millis);
    return !writer.isAlive();
  }
}
