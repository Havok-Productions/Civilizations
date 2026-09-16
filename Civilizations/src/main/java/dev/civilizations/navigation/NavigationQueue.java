package dev.civilizations.navigation;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Fair asynchronous admission. A worker/channel retains only its latest requested operation. */
public final class NavigationQueue<K, V> implements AutoCloseable {
  private final int concurrency;
  private final Map<K, Entry> latest = new HashMap<>();
  private final ArrayDeque<Entry> waiting = new ArrayDeque<>();
  private final Set<Entry> running = new HashSet<>();
  private boolean closed;

  private final class Entry {
    final K key;
    final Object identity;
    final Supplier<CompletableFuture<V>> operation;
    final CompletableFuture<V> result = new CompletableFuture<>();

    Entry(K key, Object identity, Supplier<CompletableFuture<V>> operation) {
      this.key = key;
      this.identity = identity;
      this.operation = operation;
    }
  }

  public NavigationQueue(int concurrency) {
    if (concurrency < 1) throw new IllegalArgumentException("Positive concurrency required");
    this.concurrency = concurrency;
  }

  public CompletableFuture<V> submit(K key, Object identity, Supplier<CompletableFuture<V>> work) {
    Entry old, entry;
    synchronized (this) {
      if (closed)
        return CompletableFuture.failedFuture(new CancellationException("navigation_closed"));
      old = latest.get(key);
      if (old != null && !old.result.isDone() && old.identity.equals(identity)) return old.result;
      entry = new Entry(key, identity, work);
      latest.put(key, entry);
      waiting.add(entry);
      if (old != null) waiting.remove(old);
    }
    if (old != null) old.result.cancel(false);
    pump();
    return entry.result;
  }

  public void cancel(K key) {
    Entry old;
    synchronized (this) {
      old = latest.remove(key);
      if (old != null) waiting.remove(old);
    }
    if (old != null) old.result.cancel(false);
  }

  private void pump() {
    List<Entry> ready = new ArrayList<>();
    synchronized (this) {
      while (!closed && running.size() < concurrency && !waiting.isEmpty()) {
        Entry entry = waiting.remove();
        if (entry.result.isDone()) {
          latest.remove(entry.key, entry);
          continue;
        }
        running.add(entry);
        ready.add(entry);
      }
    }
    for (Entry entry : ready) {
      CompletableFuture<V> operation;
      try {
        operation =
            entry.result.isDone()
                ? CompletableFuture.failedFuture(new CancellationException("navigation_superseded"))
                : entry.operation.get();
      } catch (RuntimeException error) {
        operation = CompletableFuture.failedFuture(error);
      }
      operation.whenComplete(
          (value, error) -> {
            synchronized (this) {
              running.remove(entry);
              latest.remove(entry.key, entry);
            }
            // A cancelled observer does not release admission until the actual operation ends.
            if (error == null) entry.result.complete(value);
            else entry.result.completeExceptionally(error);
            pump();
          });
    }
  }

  @Override
  public void close() {
    Set<Entry> entries;
    synchronized (this) {
      closed = true;
      entries = new HashSet<>(running);
      entries.addAll(waiting);
      latest.clear();
      waiting.clear();
    }
    entries.forEach(e -> e.result.cancel(false));
  }
}
