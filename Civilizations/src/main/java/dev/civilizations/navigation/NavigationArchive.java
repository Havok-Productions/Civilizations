package dev.civilizations.navigation;

import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

/** Bounded map archive. IDs inside each file disambiguate slots reused by the ring. */
public final class NavigationArchive implements AutoCloseable {
  private final Path root;
  private final AtomicLong sequence = new AtomicLong(), dropped = new AtomicLong();
  private final ThreadPoolExecutor writer;
  private final Consumer<String> warning;

  public NavigationArchive(Path root, Consumer<String> warning) {
    this.root = root;
    this.warning = warning;
    writer =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16),
            r -> {
              Thread t = new Thread(r, "Civilizations-navigation-maps");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  public String save(
      String id,
      String village,
      String worker,
      NavigationMap map,
      TerrainRouteSearch.Result result) {
    String name = "map-" + (sequence.getAndIncrement() % 128) + ".json.gz";
    try {
      writer.execute(
          () -> {
            try {
              Files.createDirectories(root);
              try (var out = new GZIPOutputStream(Files.newOutputStream(root.resolve(name)))) {
                out.write(
                    new Gson()
                        .toJson(
                            Map.of(
                                "schema",
                                1,
                                "id",
                                id,
                                "time",
                                System.currentTimeMillis(),
                                "village",
                                village,
                                "worker",
                                worker,
                                "map",
                                map.describe(),
                                "search",
                                result))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
              }
            } catch (Exception e) {
              warning.accept("Navigation map archive failed: " + e.getMessage());
            }
          });
      return name;
    } catch (RejectedExecutionException e) {
      dropped.incrementAndGet();
      return "archive_queue_full";
    }
  }

  public long dropped() {
    return dropped.get();
  }

  public Map<String, List<RouteMemory.Saved>> loadMemory() {
    Path file = root.resolve("failed-transitions.json");
    if (!Files.exists(file)) return Map.of();
    try (var reader = Files.newBufferedReader(file)) {
      Map<String, List<RouteMemory.Saved>> result =
          new Gson()
              .fromJson(
                  reader,
                  new com.google.gson.reflect.TypeToken<
                      Map<String, List<RouteMemory.Saved>>>() {}.getType());
      return result == null ? Map.of() : result;
    } catch (Exception error) {
      warning.accept("Could not restore navigation failures: " + error.getMessage());
      return Map.of();
    }
  }

  public void saveMemory(Map<String, List<RouteMemory.Saved>> memory) {
    try {
      writer.execute(
          () -> {
            try {
              Files.createDirectories(root);
              Path temp = root.resolve("failed-transitions.tmp");
              Files.writeString(temp, new Gson().toJson(memory));
              Files.move(
                  temp,
                  root.resolve("failed-transitions.json"),
                  StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception error) {
              warning.accept("Could not save navigation failures: " + error.getMessage());
            }
          });
    } catch (RejectedExecutionException error) {
      dropped.incrementAndGet();
      warning.accept("Navigation memory write queue full");
    }
  }

  public void close() {
    writer.shutdown();
  }

  public boolean awaitClosed(long milliseconds) throws InterruptedException {
    return writer.awaitTermination(milliseconds, TimeUnit.MILLISECONDS);
  }
}
