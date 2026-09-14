package dev.coreai.agent;

import java.util.*;
import java.util.function.Consumer;

/** One agent's working memory, with an optional host-owned persistence sink. */
public final class RecentMemory implements AgentPorts.Memory {
  private final String agent;
  private final int capacity;
  private final Consumer<AgentSession.Experience> sink;
  private final ArrayDeque<AgentSession.Experience> entries = new ArrayDeque<>();

  public RecentMemory(String agent, int capacity, Consumer<AgentSession.Experience> sink) {
    this.agent = Objects.requireNonNull(agent);
    if (capacity < 1) throw new IllegalArgumentException("Memory storage must hold an entry");
    this.capacity = capacity;
    this.sink = Objects.requireNonNull(sink);
  }

  public void remember(AgentSession.Experience experience) {
    if (!agent.equals(experience.attempt().observation().agent()))
      throw new IllegalArgumentException("Experience belongs to another agent");
    synchronized (this) {
      if (entries.size() == capacity) entries.removeFirst();
      entries.addLast(experience);
    }
    sink.accept(experience);
  }

  public synchronized List<AgentSession.Experience> recent(String requestedAgent) {
    return agent.equals(requestedAgent) ? List.copyOf(entries) : List.of();
  }
}
