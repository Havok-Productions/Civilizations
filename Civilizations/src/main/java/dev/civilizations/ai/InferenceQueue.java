package dev.civilizations.ai;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/** One actual inference at a time, bounded queue, one outstanding call per agent. */
public final class InferenceQueue implements AutoCloseable {
  private final ModelBackend backend;
  private final ThreadPoolExecutor executor;
  private final Set<String> pending = ConcurrentHashMap.newKeySet();
  private final Semaphore admission;
  private final int reserved;
  private final AtomicLong sequence = new AtomicLong(), finalRetries = new AtomicLong();
  private final AtomicLong successes = new AtomicLong(), failures = new AtomicLong();
  private final AtomicLong recoveryRequests = new AtomicLong();
  private final AtomicLong designResponses = new AtomicLong();
  private volatile String lastError = "";
  private java.util.function.BiConsumer<String, Map<String, ?>> observer = (a, e) -> {};

  public void observe(java.util.function.BiConsumer<String, Map<String, ?>> observer) {
    this.observer = observer;
  }

  public InferenceQueue(ModelBackend backend, int capacity) {
    this.backend = backend;
    admission = new Semaphore(capacity + 1);
    reserved = capacity >= 4 ? 1 : 0;
    executor =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new PriorityBlockingQueue<>(
                capacity, Comparator.comparingLong(r -> ((Queued) r).order)),
            r -> {
              Thread t = new Thread(r, "Civilizations-inference");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  private static final class Queued implements Runnable {
    final long order;
    final Runnable work;

    Queued(long order, Runnable work) {
      this.order = order;
      this.work = work;
    }

    public void run() {
      work.run();
    }
  }

  private synchronized boolean admit(ReasoningMode mode) {
    return !(mode == ReasoningMode.NORMAL && admission.availablePermits() <= reserved)
        && admission.tryAcquire();
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
    if (!backend.ready() || !pending.add(agent)) return false;
    if (!admit(mode)) {
      pending.remove(agent);
      return false;
    }
    try {
      // Planning/recovery can pass recent routine requests. Older requests eventually take
      // priority.
      long order =
          (System.currentTimeMillis() - (mode == ReasoningMode.NORMAL ? 0 : 10_000)) * 1000
              + sequence.getAndIncrement() % 1000;
      executor.execute(
          new Queued(
              order,
              () -> {
                T decision = null;
                try {
                  if (System.currentTimeMillis() > deadline)
                    throw new TimeoutException("Queued decision expired before inference");
                  if (mode == ReasoningMode.RECOVERY) recoveryRequests.incrementAndGet();
                  try {
                    decision =
                        parser.apply(backend.complete(system, report, mode, schema, deadline));
                  } catch (Exception e) {
                    if (e instanceof InterruptedException) throw e;
                    if (deadline - System.currentTimeMillis() < 2000) throw e;
                    observer.accept(
                        agent, Map.of("stage", "final_answer_retry", "reason", e.toString()));
                    finalRetries.incrementAndGet();
                    decision =
                        parser.apply(
                            backend.recover(
                                system
                                    + "\n"
                                    + "Return the final JSON now. Use only supplied observations"
                                    + " and supported actions.",
                                report,
                                schema,
                                deadline));
                  }
                  if (System.currentTimeMillis() > deadline) {
                    decision = null;
                    throw new TimeoutException("Decision completed after its observations expired");
                  }
                  if (mode == ReasoningMode.DESIGN) designResponses.incrementAndGet();
                  else successes.incrementAndGet();
                } catch (Exception e) {
                  decision = null;
                  failures.incrementAndGet();
                  lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                  observer.accept(agent, Map.of("stage", "failed", "error", lastError));
                } finally {
                  pending.remove(agent);
                  admission.release();
                }
                callback.accept(decision);
              }));
      return true;
    } catch (RejectedExecutionException e) {
      pending.remove(agent);
      admission.release();
      return false;
    }
  }

  public String status() {
    return backend.status()
        + "; queued="
        + executor.getQueue().size()
        + ", decisions="
        + successes
        + ", failures="
        + failures
        + ", recovery-thinking="
        + recoveryRequests
        + ", design-responses="
        + designResponses
        + ", final-answer-retries="
        + finalRetries
        + (lastError.isEmpty()
            ? ""
            : "; last error=" + lastError.substring(0, Math.min(200, lastError.length())));
  }

  /** Learning is admitted only when there is no foreground inference pending. */
  public boolean idle() {
    return backend.ready() && pending.isEmpty();
  }

  @Override
  public void close() {
    executor.shutdownNow();
    pending.clear();
    backend.close();
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
