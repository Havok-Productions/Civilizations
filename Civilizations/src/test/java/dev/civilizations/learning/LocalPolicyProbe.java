package dev.civilizations.learning;

import com.google.gson.Gson;
import dev.civilizations.ai.*;
import dev.coreai.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Explicit local-model integration check. Never part of automatic unit tests. */
public final class LocalPolicyProbe {
  public static void main(String[] args) throws Exception {
    var config = YamlConfiguration.loadConfiguration(Path.of(args[0]).toFile());
    Path root = Path.of(args[1]);
    for (int i = 0; i < 2; i++) {
      String prefix = i == 0 ? "ai" : "ai.deep-thinking";
      String name = i == 0 ? "qwen-jobs" : "deepseek-routes";
      var cases = i == 0 ? VillagerPolicyCases.jobs() : VillagerPolicyCases.routes();
      var library = new PolicyLibrary(root.resolve(name + "-policies"), cases);
      try (var runtime =
          new LocalRuntime(
              LocalRuntime.Settings.read(config, prefix),
              root.resolve(name),
              System.out::println)) {
        runtime.start();
        long readyDeadline = System.currentTimeMillis() + 300_000;
        while (!runtime.ready() && System.currentTimeMillis() < readyDeadline) Thread.sleep(200);
        if (!runtime.ready())
          throw new AssertionError("Runtime failed to become ready: " + runtime.status());
        String feedback = "No previous attempt";
        boolean passed = false;
        for (int attempt = 0; attempt < 3 && !passed; attempt++) {
          String report =
              new Gson()
                  .toJson(
                      Map.of(
                          "scope",
                          i == 0 ? "JOBS" : "ROUTES",
                          "active_source",
                          library.version().source(),
                          "cases",
                          library.report(),
                          "last_review",
                          feedback));
          long start = System.currentTimeMillis();
          String raw =
              runtime.complete(
                  PolicyTeacher.SYSTEM,
                  report,
                  i == 0 || attempt > 0 ? ReasoningMode.FINAL : ReasoningMode.RECOVERY,
                  PolicyTeacher.SCHEMA,
                  start + 180_000);
          var proposal = PolicyTeacher.parse(raw);
          Map<String, Object> record = new LinkedHashMap<>();
          record.put("proposal", proposal);
          record.put("teacher", TeacherProvenance.read(config, prefix));
          try {
            var result = library.propose(proposal.source(), TeacherProvenance.read(config, prefix));
            record.put("evaluation", result);
            passed = result.accepted();
            feedback = "Candidate source: " + proposal.source() + "; host evaluation: " + result;
          } catch (IllegalArgumentException invalid) {
            feedback =
                "Rejected source: "
                    + proposal.source()
                    + "; interpreter error: "
                    + invalid.getMessage();
            record.put("error", feedback);
          }
          record.put("elapsed_ms", System.currentTimeMillis() - start);
          record.put("basis", "local model plus host replay; no live-world performance claim");
          Files.writeString(
              root.resolve(name + "-attempt" + attempt + ".json"), new Gson().toJson(record));
          System.out.println(
              name
                  + " attempt="
                  + attempt
                  + " passed="
                  + passed
                  + " "
                  + feedback
                  + " elapsed_ms="
                  + (System.currentTimeMillis() - start));
        }
        if (!passed)
          System.out.println(name + " NO PROMOTION: bounded attempts exhausted; baseline retained");
      }
    }
  }
}
