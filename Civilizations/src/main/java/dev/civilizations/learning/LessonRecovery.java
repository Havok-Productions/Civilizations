package dev.civilizations.learning;

import com.google.gson.*;
import dev.civilizations.world.BlockObservation;
import dev.coreai.TerrainRuleBook;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Recover measured facts discarded by older trial handling, never failed behavioral programs. */
public final class LessonRecovery {
  private static final Gson JSON = new Gson();

  private LessonRecovery() {}

  public static int restore(Path root, TerrainRuleBook rules) throws IOException {
    Path marker = root.resolve("rules/verified-observation-import-v1.json");
    if (Files.exists(marker)) return 0;
    int recovered = 0;
    Path data = root.resolve("data");
    if (Files.isDirectory(data))
      try (var files = Files.newDirectoryStream(data, "experiments*.jsonl")) {
        for (Path file : files)
          try (var reader = Files.newBufferedReader(file)) {
            for (String line; (line = reader.readLine()) != null; ) {
              try {
                var entry = JsonParser.parseString(line).getAsJsonObject();
                if (!entry.has("event") || !entry.get("event").getAsString().equals("step"))
                  continue;
                var evidence = entry.getAsJsonObject("data").getAsJsonObject("evidence");
                if (evidence == null
                    || !evidence.has("physical_probe")
                    || !evidence.has("proposed_rule")) continue;
                var facts =
                    JSON.fromJson(evidence.get("physical_probe"), TerrainRuleBook.Facts.class);
                var proposal = evidence.getAsJsonObject("proposed_rule");
                String category = proposal.get("category").getAsString();
                if (!facts.equals(
                    JSON.fromJson(proposal.get("facts"), TerrainRuleBook.Facts.class))) continue;
                if (BlockObservation.dangerous(facts.material(), facts.state())) continue;
                if (rules.learnIfAbsent(
                    facts,
                    category,
                    "Recovered recorded physical probe; trial outcome is separate",
                    "host:recorded-physical-observation")) recovered++;
              } catch (RuntimeException invalid) {
                // Old contradictory classifications and partial journal lines are not lessons.
              }
            }
          }
      }
    rules.flush();
    Files.writeString(
        marker,
        JSON.toJson(
            Map.of(
                "recovered",
                recovered,
                "basis",
                "validated physical probes only; no behavioral success inferred")));
    return recovered;
  }
}
