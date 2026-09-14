package dev.coreai.agent;

import java.util.*;

/** One coherent observation and the actions currently supported by its host. */
public record AgentFrame(Observation observation, List<Goal> goals, List<Action> actions) {
  public AgentFrame {
    Objects.requireNonNull(observation);
    goals = List.copyOf(goals);
    actions = List.copyOf(actions);
    Set<String> goalIds = new HashSet<>(), actionIds = new HashSet<>();
    for (Goal goal : goals)
      if (!goalIds.add(goal.id())) throw new IllegalArgumentException("Duplicate goal");
    for (Action action : actions) {
      if (!actionIds.add(action.id())) throw new IllegalArgumentException("Duplicate action");
      if (!goalIds.contains(action.goal()))
        throw new IllegalArgumentException("Unknown action goal");
    }
  }
}
