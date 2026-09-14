package dev.coreai.agent;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Per-agent observe -> select -> execute -> verified experience lifecycle, without a game loop. */
public final class AgentSession implements AutoCloseable {
  public record Attempt(
      String id, Observation observation, Goal goal, Action action, long startedAt) {}

  public record Experience(Attempt attempt, Outcome outcome, long finishedAt) {}

  private final String agent;
  private final AgentPorts.Memory memory;
  private final LongSupplier clock;
  private final Consumer<String> warning;
  private AgentFrame frame;
  private Attempt active;
  private boolean closed;
  private long lastObservation = Long.MIN_VALUE;

  public AgentSession(
      String agent, AgentPorts.Memory memory, LongSupplier clock, Consumer<String> warning) {
    this.agent = Objects.requireNonNull(agent);
    this.memory = Objects.requireNonNull(memory);
    this.clock = Objects.requireNonNull(clock);
    this.warning = Objects.requireNonNull(warning);
  }

  public AgentFrame observe(AgentPorts.Perception perception, AgentPorts.Planning planner) {
    Observation observed = perception.observe();
    AgentFrame next = planner.plan(observed);
    if (!next.observation().equals(observed))
      throw new IllegalArgumentException("Plan does not belong to this observation");
    observe(next);
    return next;
  }

  public synchronized void observe(AgentFrame next) {
    if (closed) throw new IllegalStateException("Agent session closed");
    if (!agent.equals(next.observation().agent()))
      throw new IllegalArgumentException("Wrong agent");
    if (next.observation().observedAt() < lastObservation)
      throw new IllegalArgumentException("Observation is older than the current frame");
    frame = next;
    lastObservation = next.observation().observedAt();
  }

  /** Calls the host on the calling thread; completion may arrive later on any thread. */
  public boolean start(String actionId, AgentPorts.ActionExecutor executor) {
    Attempt attempt;
    CompletionStage<Outcome> completion;
    synchronized (this) {
      if (closed || active != null || frame == null) return false;
      Action action =
          frame.actions().stream().filter(a -> a.id().equals(actionId)).findFirst().orElse(null);
      if (action == null) return false;
      Goal goal =
          frame.goals().stream()
              .filter(g -> g.id().equals(action.goal()))
              .findFirst()
              .orElseThrow();
      attempt =
          new Attempt(
              UUID.randomUUID().toString(), frame.observation(), goal, action, clock.getAsLong());
      active = attempt;
      frame = null; // A completed action cannot be restarted from the same stale offer.
      try {
        // Dispatch is short and synchronous on the host thread. Cancellation cannot overtake it.
        completion =
            Objects.requireNonNull(executor.start(attempt), "Executor returned no completion");
      } catch (Exception error) {
        completion =
            CompletableFuture.completedFuture(
                new Outcome(
                    Outcome.Status.FAILED,
                    "executor_start_failed",
                    Facts.of(Map.of("error", error.toString()))));
      }
    }
    completion.whenComplete(
        (outcome, error) ->
            finish(
                attempt,
                error == null && outcome != null
                    ? outcome
                    : new Outcome(
                        Outcome.Status.FAILED,
                        "executor_error",
                        Facts.of(Map.of("error", String.valueOf(error))))));
    return true;
  }

  private void finish(Attempt attempt, Outcome outcome) {
    synchronized (this) {
      if (active == null || !active.id().equals(attempt.id())) return;
      active = null;
    }
    remember(new Experience(attempt, outcome, clock.getAsLong()));
  }

  public void cancel(String reason) {
    Attempt attempt;
    synchronized (this) {
      frame = null;
      attempt = active;
      active = null;
    }
    if (attempt != null)
      remember(new Experience(attempt, Outcome.cancelled(reason), clock.getAsLong()));
  }

  private void remember(Experience experience) {
    try {
      memory.remember(experience);
    } catch (RuntimeException error) {
      warning.accept("Agent memory could not persist an outcome: " + error);
    }
  }

  public synchronized Attempt active() {
    return active;
  }

  public List<Experience> recent() {
    return memory.recent(agent);
  }

  public void close() {
    synchronized (this) {
      closed = true;
    }
    cancel("agent_session_closed");
  }
}
