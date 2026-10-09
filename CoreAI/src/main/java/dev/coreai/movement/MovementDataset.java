package dev.coreai.movement;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Shared disk snapshot for automatic diagnostics and manual/automatic training. */
final class MovementDataset {
  record Data(
      List<MovementSample> samples,
      int malformed,
      int unfinished,
      int finished,
      int successes,
      int failures,
      int interrupted,
      Map<String, Integer> reasons,
      String signature) {}

  private static final Gson JSON = new Gson();

  private MovementDataset() {}

  static Data read(Path root) throws IOException {
    List<MovementSample> samples = new ArrayList<>(MovementCurriculum.generate(451, 200));
    Path memory = root.resolve("experience.json");
    if (Files.exists(memory)) {
      var remembered = JSON.fromJson(Files.readString(memory), MovementSample[].class);
      for (var sample : remembered) {
        if (!sample.source().equals("physical") || sample.success() == null)
          throw new IOException("Invalid durable movement experience; existing file retained");
        samples.add(sample);
      }
    }
    int malformed = 0, successes = 0, failures = 0, interrupted = 0;
    Set<String> starts = new HashSet<>(), ends = new HashSet<>();
    Map<String, Integer> reasons = new TreeMap<>();
    for (int i = 3; i >= 0; i--) {
      Path file = root.resolve(i == 0 ? "attempts.jsonl" : "attempts." + i + ".jsonl");
      if (!Files.exists(file)) continue;
      try (var lines = Files.newBufferedReader(file)) {
        String line;
        while ((line = lines.readLine()) != null) {
          try {
            var receipt = JSON.fromJson(line, MovementLearning.Receipt.class);
            var sample = receipt.sample();
            if (receipt.schema() != 1 || !sample.source().equals("physical"))
              throw new IllegalArgumentException();
            if ("finished".equals(receipt.phase())) {
              if (!ends.add(sample.id())) continue;
              samples.add(sample);
              if (sample.success() == null) interrupted++;
              else if (sample.success()) successes++;
              else failures++;
              reasons.merge(sample.reason(), 1, Integer::sum);
            } else if ("started".equals(receipt.phase())) starts.add(sample.id());
            else throw new IllegalArgumentException("Unknown receipt phase");
          } catch (RuntimeException bad) {
            malformed++;
          }
        }
      }
    }
    starts.removeAll(ends);
    // Same examples in a different order, repeated trials and cancellations do not retrigger
    // training.
    var keys =
        samples.stream()
            .filter(s -> s.source().equals("physical") && s.success() != null)
            .map(s -> s.group() + ":" + s.features() + ":" + s.success())
            .distinct()
            .sorted()
            .toList();
    // Re-evaluate the existing data once when the training/promotion algorithm changes.
    String signature = MovementTraining.digest("movement-v2-balanced-validation:451:200:" + keys);
    return new Data(
        List.copyOf(samples),
        malformed,
        starts.size(),
        ends.size(),
        successes,
        failures,
        interrupted,
        Map.copyOf(reasons),
        signature);
  }
}
