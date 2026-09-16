package dev.coreai.reasoning;

import dev.coreai.reasoning.ReasoningBackend.Purpose;
import dev.coreai.reasoning.ReasoningBackend.Request;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/** One actual inference at a time, bounded queue, one outstanding call per agent. */
public final class InferenceScheduler implements AutoCloseable {
  public record Failure(String stage, String reason) {}

  private final ReasoningBackend backend;
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

  public InferenceScheduler(ReasoningBackend backend, int capacity) {
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
              Thread t = new Thread(r, "CoreAI-inference");
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

  private synchronized boolean admit(Purpose mode) {
    return !(mode == Purpose.NORMAL && admission.availablePermits() <= reserved)
        && admission.tryAcquire();
  }

  public <T> boolean submit(
      String agent,
      String system,
      String report,
      Purpose mode,
      String schema,
      long deadline,
      Function<String, T> parser,
      Consumer<T> callback) {
    return submit(agent, system, report, mode, schema, deadline, parser, callback, failure -> {});
  }

  /** The failure belongs to this request, including immediate admission refusals. */
  public <T> boolean submit(
      String agent,
      String system,
      String report,
      Purpose mode,
      String schema,
      long deadline,
      Function<String, T> parser,
      Consumer<T> callback,
      Consumer<Failure> failed) {
    if (!backend.ready()) {
      failed.accept(new Failure("backend_unavailable", backend.status()));
      return false;
    }
    if (!pending.add(agent)) {
      failed.accept(
          new Failure("already_pending", "This agent already has an outstanding request"));
      return false;
    }
    if (!admit(mode)) {
      pending.remove(agent);
      failed.accept(new Failure("queue_full", "No inference admission slot available"));
      return false;
    }
    try {
      // Planning/recovery can pass recent routine requests. Older requests eventually take
      // priority.
      long order =
          (System.currentTimeMillis() - (mode == Purpose.NORMAL ? 0 : 10_000)) * 1000
              + sequence.getAndIncrement() % 1000;
      executor.execute(
          new Queued(
              order,
              () -> {
                T decision = null;
                String stage = "expired";
                Failure failure = null;
                try {
                  if (System.currentTimeMillis() > deadline)
                    throw new TimeoutException("Queued decision expired before inference");
                  if (mode == Purpose.RECOVERY) recoveryRequests.incrementAndGet();
                  try {
                    stage = "backend_error";
                    String response =
                        backend.complete(new Request(system, report, mode, schema, deadline));
                    stage = "invalid_response";
                    decision =
                        Objects.requireNonNull(
                            parser.apply(response), "Parser returned no usable response");
                  } catch (Exception e) {
                    if (e instanceof InterruptedException) throw e;
                    if (deadline - System.currentTimeMillis() < 2000) throw e;
                    observer.accept(
                        agent, Map.of("stage", "final_answer_retry", "reason", e.toString()));
                    finalRetries.incrementAndGet();
                    stage = "backend_error";
                    String response =
                        backend.recover(
                            new Request(
                                system
                                    + "\n"
                                    + "Return the final JSON now. Use only supplied"
                                    + " observations and supported actions.",
                                report,
                                Purpose.FINAL,
                                schema,
                                deadline));
                    stage = "invalid_response";
                    decision =
                        Objects.requireNonNull(
                            parser.apply(response), "Parser returned no usable response");
                  }
                  if (System.currentTimeMillis() > deadline) {
                    decision = null;
                    stage = "expired";
                    throw new TimeoutException("Decision completed after its observations expired");
                  }
                  if (mode == Purpose.DESIGN) designResponses.incrementAndGet();
                  else successes.incrementAndGet();
                } catch (Exception e) {
                  decision = null;
                  failures.incrementAndGet();
                  lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                  failure = new Failure(stage, lastError);
                  observer.accept(
                      agent, Map.of("stage", "failed", "failure_stage", stage, "error", lastError));
                } finally {
                  pending.remove(agent);
                  admission.release();
                }
                if (failure != null) failed.accept(failure);
                callback.accept(decision);
              }));
      return true;
    } catch (RejectedExecutionException e) {
      pending.remove(agent);
      admission.release();
      failed.accept(new Failure("scheduler_stopped", e.toString()));
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
}
