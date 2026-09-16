package dev.civilizations.ai;

import dev.coreai.reasoning.*;
import java.util.*;
import java.util.function.*;

/** Minecraft prompts and response validation around the shared CoreAI inference scheduler. */
public final class InferenceQueue implements AutoCloseable {
  private final InferenceScheduler scheduler;
  private BiConsumer<String, Map<String, ?>> observer = (agent, data) -> {};

  public InferenceQueue(ModelBackend backend, int capacity) {
    scheduler = new InferenceScheduler(backend, capacity);
  }

  public void observe(BiConsumer<String, Map<String, ?>> observer) {
    this.observer = observer;
    scheduler.observe(observer);
  }

  public String status() {
    return scheduler.status();
  }

  public boolean idle() {
    return scheduler.idle();
  }

  public void close() {
    scheduler.close();
  }

  public boolean request(
      String agent, String report, Set<String> offered, Consumer<Decision> callback) {
    return request(agent, report, offered, ReasoningMode.NORMAL, callback);
  }

  public boolean request(
      String agent,
      String report,
      Set<String> offered,
      ReasoningMode mode,
      Consumer<Decision> callback) {
    return request(agent, report, offered, mode, System.currentTimeMillis() + 120_000, callback);
  }

  public boolean request(
      String agent,
      String report,
      Set<String> offered,
      ReasoningMode mode,
      long deadline,
      Consumer<Decision> callback) {
    return submit(
        agent,
        SYSTEM,
        report,
        mode,
        DecisionSchema.jobs(offered),
        deadline,
        text -> Decision.parse(text, offered),
        callback);
  }

  public <T> boolean submit(
      String agent,
      String system,
      String report,
      ReasoningMode mode,
      String schema,
      long deadline,
      Function<String, T> parser,
      Consumer<T> callback) {
    return scheduler.submit(
        agent,
        system,
        context(agent, report),
        ReasoningBackend.Purpose.valueOf(mode.name()),
        schema,
        deadline,
        parser,
        callback);
  }

  public <T> boolean submit(
      String agent,
      String system,
      String report,
      ReasoningMode mode,
      String schema,
      long deadline,
      Function<String, T> parser,
      Consumer<T> callback,
      Consumer<InferenceScheduler.Failure> failed) {
    return scheduler.submit(
        agent,
        system,
        context(agent, report),
        ReasoningBackend.Purpose.valueOf(mode.name()),
        schema,
        deadline,
        parser,
        callback,
        failed);
  }

  private String context(String agent, String report) {
    String compact = ModelContext.compact(report);
    observer.accept(
        agent,
        Map.of(
            "stage",
            "working_context",
            "full_characters",
            report.length(),
            "sent_characters",
            compact.length(),
            "context",
            compact));
    return compact;
  }

  static final String SYSTEM =
      """
      You control one ordinary villager. Solve the village's observed needs using only the offered jobs, real inventory, available resource sites, planning constraints, and recent failures.
      Your actions are work (choose an offered job_id) or replan (request new observations/planning). Rest is offered only when no jobs are available. Always leave material empty. Work automatically checks community chests, withdraws materials, gathers, crafts, walks, and executes the objective. Do not invent a separate gather/withdraw action: those are already work steps.
      Work selects one listed job_id and commits to its project. The worker automatically gathers missing ingredients, retaining them until that whole project/crop cycle completes. Farm jobs plant wheat or harvest and replant mature wheat, retaining food and seeds. Prefer food work when food is low, repair/walls/lights when mobs were observed, and resource production needed by actual recipes.
      Inventory is what YOU carry; stockpile belongs to a chest. Work handles all prerequisite gathering and crafting automatically. LOG means compatible natural wood, including birch. Never gather stone for torches: they need fuel and sticks/wood. The executor deposits surplus only after the whole committed task is complete. There is no stamina or energy meter. Rest only when no feasible work or a real safety/availability constraint prevents progress.
      When recovery_problem is present, reconsider the failed route/resource/site and select a feasible offered alternative instead of repeating the same action. You may defer blocked work and request a rescan with action replan. Do not invent facts, buildings, coordinates, resources, or commands.
      task_plans describe actual prerequisites. Coal mining needs a wooden pickaxe: obtain wood, craft planks/sticks and a table, then craft the pickaxe. Iron/copper require a stone pickaxe. Tools consume durability. When no safe coal is available, request a mine supply plan; a shaft does not guarantee coal. Shared blocked facts are observed failures with retry times. recent_results contains observations, not prior model suggestions. Prefer achievable steps while blocked prerequisites are being resolved. Keep routine reasoning brief.
      Movement, safety, sleep, site selection and resource validation are enforced by the plugin. You cannot issue commands or arbitrary coordinates. A job may be claimed by another worker before you act.
      Return only a JSON object with action, job_id (empty unless work), material (always empty), and reason (one short sentence). No markdown or reasoning transcript.
      """;
}
