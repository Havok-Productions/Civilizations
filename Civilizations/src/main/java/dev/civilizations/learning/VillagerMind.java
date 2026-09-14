package dev.civilizations.learning;

import dev.civilizations.core.*;
import dev.coreai.agent.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Translates claimed Minecraft work into the reusable lifecycle; contains no world access. */
public final class VillagerMind implements AutoCloseable {
  private final String worker;
  private final Settlement village;
  private final AgentSession session;
  private CompletableFuture<Outcome> completion;

  public VillagerMind(
      String worker,
      Settlement village,
      Consumer<AgentSession.Experience> sink,
      Consumer<String> warning) {
    this.worker = worker;
    this.village = village;
    session =
        new AgentSession(
            worker, new RecentMemory(worker, 16, sink), System::currentTimeMillis, warning);
  }

  public void begin(Job job, Pos at, Map<String, Integer> inventory, long now, Runnable startWork) {
    cancel("new_work_selected");
    AgentPorts.Perception perception =
        () ->
            new Observation(
                worker,
                village.world(),
                now,
                Facts.of(
                    Map.of(
                        "village",
                        village.id(),
                        "position",
                        position(at),
                        "inventory",
                        inventory,
                        "community_stock",
                        village.stock(),
                        "stock_age_ms",
                        village.stockAge(now),
                        "recent_hostile_mobs",
                        village.needs().danger(now),
                        "project",
                        job.project)));
    AgentPorts.Planning planning =
        observation -> {
          Facts parameters =
              Facts.of(
                  Map.of(
                      "project",
                      job.project,
                      "target",
                      position(job.target),
                      "material",
                      Objects.toString(job.material, ""),
                      "kind",
                      job.kind.name()));
          Goal goal =
              new Goal(job.id, "Verify " + job.kind + " step in " + job.project, parameters);
          Action action =
              new Action(
                  job.id,
                  goal.id(),
                  "minecraft:" + job.kind.name().toLowerCase(Locale.ROOT),
                  parameters);
          return new AgentFrame(observation, List.of(goal), List.of(action));
        };
    session.observe(perception, planning);
    completion = new CompletableFuture<>();
    CompletableFuture<Outcome> pending = completion;
    if (!session.start(
            job.id,
            attempt -> {
              startWork.run();
              return pending;
            })
        || session.active() == null)
      throw new IllegalStateException("Could not start claimed agent action");
  }

  public void succeeded(String job, Map<String, ?> evidence) {
    var active = session.active();
    if (active != null && active.action().id().equals(job) && completion != null)
      completion.complete(
          new Outcome(Outcome.Status.SUCCEEDED, "executor_verified_job_state", Facts.of(evidence)));
  }

  public void failed(String reason, Map<String, ?> evidence) {
    if (completion != null && session.active() != null)
      completion.complete(new Outcome(Outcome.Status.FAILED, reason, Facts.of(evidence)));
  }

  /**
   * The worker owns physical cancellation; the session cancels attribution and ignores late
   * receipts.
   */
  public void cancel(String reason) {
    session.cancel(reason);
    if (completion != null) completion.cancel(false);
    completion = null;
  }

  public List<AgentSession.Experience> experiences() {
    return session.recent();
  }

  public List<Map<String, Object>> report() {
    var recent = experiences();
    return recent.stream()
        .skip(Math.max(0, recent.size() - 4))
        .map(
            e ->
                Map.<String, Object>of(
                    "goal",
                    e.attempt().goal().description(),
                    "action",
                    e.attempt().action().capability(),
                    "status",
                    e.outcome().status().name(),
                    "reason",
                    e.outcome().reason(),
                    "finished_at",
                    e.finishedAt()))
        .toList();
  }

  public String status() {
    var active = session.active();
    return "CoreAI action="
        + (active == null ? "none" : active.action().capability())
        + "; recent verified experiences="
        + experiences().stream()
            .filter(e -> e.outcome().status() == Outcome.Status.SUCCEEDED)
            .count();
  }

  public void close() {
    session.close();
    if (completion != null) completion.cancel(false);
  }

  public static Map<String, Integer> position(Pos p) {
    return Map.of("x", p.x(), "y", p.y(), "z", p.z());
  }
}
