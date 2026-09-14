package dev.civilizations.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Called exclusively by the plugin's single persistence worker. */
public final class StateStore {
  private final Path dir;
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

  public StateStore(Path dir) {
    this.dir = dir;
  }

  public List<Settlement> load(Consumer<String> error) throws IOException {
    Files.createDirectories(dir);
    List<Settlement> result = new ArrayList<>();
    try (var files = Files.list(dir)) {
      for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList())
        try {
          Settlement.Data d = gson.fromJson(Files.readString(p), Settlement.Data.class);
          if (d == null || d.schema != 1 || d.center == null || d.world == null)
            throw new IOException("Invalid schema");
          UUID.fromString(d.id);
          UUID.fromString(d.world);
          result.add(new Settlement(d));
        } catch (Exception e) {
          error.accept("Cannot load " + p.getFileName() + ": " + e.getMessage());
          throw new IOException(
              "Restore or repair invalid settlement " + p.getFileName() + " before restarting", e);
        }
    }
    Set<String> absorbed = new HashSet<>();
    result.forEach(v -> absorbed.addAll(v.absorbedIds()));
    return result.stream().filter(v -> !absorbed.contains(v.id())).toList();
  }

  public void save(Collection<Settlement.Data> data) throws IOException {
    Files.createDirectories(dir);
    for (Settlement.Data d : data) {
      UUID.fromString(d.id);
      Path dest = dir.resolve(d.id + ".json"), tmp = dir.resolve(d.id + ".tmp");
      Files.writeString(tmp, gson.toJson(d));
      try {
        Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }
}
