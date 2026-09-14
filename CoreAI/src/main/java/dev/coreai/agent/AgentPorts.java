package dev.coreai.agent;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Hosts own thread scheduling and world changes; the framework receives data and receipts. */
public final class AgentPorts {
  private AgentPorts() {}

  @FunctionalInterface
  public interface Perception {
    Observation observe();
  }

  @FunctionalInterface
  public interface Planning {
    AgentFrame plan(Observation observation);
  }

  @FunctionalInterface
  public interface ActionExecutor {
    CompletionStage<Outcome> start(AgentSession.Attempt attempt) throws Exception;
  }

  public interface Memory {
    void remember(AgentSession.Experience experience);

    List<AgentSession.Experience> recent(String agent);
  }
}
