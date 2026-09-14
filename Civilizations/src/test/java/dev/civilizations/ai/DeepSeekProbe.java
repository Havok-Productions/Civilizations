package dev.civilizations.ai;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Explicitly invoked local model evaluation; excluded from the ordinary unit test run. */
public class DeepSeekProbe {
  public static void main(String[] args) throws Exception {
    var config = YamlConfiguration.loadConfiguration(Path.of(args[0]).toFile());
    try (var deep =
        new LocalRuntime(
            LocalRuntime.Settings.read(config, "ai.deep-thinking"),
            Path.of(args[1]),
            System.out::println)) {
      deep.start();
      long deadline = System.currentTimeMillis() + 600_000;
      while (!deep.ready() && System.currentTimeMillis() < deadline) Thread.sleep(500);
      if (!deep.ready()) throw new AssertionError(deep.status());
      // The baseline engine deliberately fails to exercise the installed routing boundary;
      // the second engine is the real downloaded DeepSeek model.
      ModelBackend failedPrimary =
          new ModelBackend() {
            public boolean ready() {
              return true;
            }

            public String status() {
              return "injected primary timeout";
            }

            public String complete(String s, String u) throws Exception {
              throw new java.io.IOException("injected Qwen timeout");
            }

            public void close() {}
          };
      var cases =
          List.of(
              "Observed task: repair a missing oak plank in an existing house. Inventory empty."
                  + " Community chest contains 8 OAK_PLANKS; known chest route is reachable."
                  + " Offered job repair: PLACE OAK_PLANKS. No need to cut trees. Execution"
                  + " supports withdrawing required materials.",
              "Observed task: place torch, inventory empty. Stockpile contains 4 COAL and 8"
                  + " OAK_LOG. Offered job light: PLACE TORCH. Current failed task sought new coal"
                  + " ore unnecessarily; available chest route is clear. Supported actions include"
                  + " work, gather, replan, rest.",
              "Observed task: build stone wall. Inventory empty, chest empty, nearby birch tree"
                  + " observed, exposed STONE observed. Offered job wall: PLACE COBBLESTONE."
                  + " Crafting recipes and gathering are supported. Previous failure was looking"
                  + " only for oak. Generic LOG accepts birch.");
      int passed = 0;
      try (var queue =
          new InferenceQueue(
              new ThinkingModels(
                  failedPrimary, deep, (stage, event) -> System.out.println(stage + " " + event)),
              4)) {
        queue.observe((agent, event) -> System.out.println("EVALUATION " + agent + " " + event));
        for (int i = 0; i < (args.length > 3 ? 0 : cases.size()); i++) {
          String id = List.of("repair", "light", "wall").get(i);
          long start = System.currentTimeMillis();
          var done = new CountDownLatch(1);
          var answer = new AtomicReference<Decision>();
          boolean accepted =
              queue.request(
                  "probe-" + i,
                  cases.get(i),
                  Set.of(id),
                  i == 0 ? ReasoningMode.NORMAL : ReasoningMode.RECOVERY,
                  start + 180_000,
                  d -> {
                    answer.set(d);
                    done.countDown();
                  });
          if (!accepted || !done.await(185, TimeUnit.SECONDS))
            throw new AssertionError("Probe timed out");
          Decision d = answer.get();
          boolean ok = d != null && d.action().equals("work") && d.jobId().equals(id);
          if (ok) passed++;
          else System.out.println("FAILED RESPONSE: " + queue.status());
          System.out.println(
              "DEEPSEEK CASE "
                  + id
                  + " pass="
                  + ok
                  + " elapsed_ms="
                  + (System.currentTimeMillis() - start)
                  + " action="
                  + d);
        }
        System.out.println(
            "DEEPSEEK RESULT " + passed + "/" + cases.size() + "; " + queue.status());
        if (args.length <= 3 && passed != cases.size())
          throw new AssertionError("DeepSeek did not solve all grounded probes");
        if (args.length > 2) {
          String report = Files.readString(Path.of(args[2]));
          var jobs =
              com.google.gson.JsonParser.parseString(report)
                  .getAsJsonObject()
                  .getAsJsonArray("jobs");
          Set<String> ids = new HashSet<>();
          jobs.forEach(j -> ids.add(j.getAsJsonObject().get("id").getAsString()));
          var done = new CountDownLatch(1);
          var result = new AtomicReference<Decision>();
          long start = System.currentTimeMillis();
          if (!queue.request(
                  "real-observation",
                  report,
                  ids,
                  ReasoningMode.RECOVERY,
                  start + 180_000,
                  d -> {
                    result.set(d);
                    done.countDown();
                  })
              || !done.await(185, TimeUnit.SECONDS))
            throw new AssertionError("Real report timeout");
          Decision decision = result.get();
          boolean ok =
              decision != null
                  && decision.action().equals("work")
                  && ids.contains(decision.jobId());
          System.out.println(
              "REAL OBSERVATIONS pass="
                  + ok
                  + " elapsed_ms="
                  + (System.currentTimeMillis() - start)
                  + " action="
                  + decision);
          if (!ok) throw new AssertionError("No feasible action selected from real observations");
        }
      }
    }
  }
}
