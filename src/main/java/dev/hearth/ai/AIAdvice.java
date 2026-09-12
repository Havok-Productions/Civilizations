package dev.hearth.ai;

import dev.hearth.HearthPlugin;
import dev.hearth.brain.TaskType;
import dev.hearth.village.Village;

/**
 * Result of an AI advisor call: which task the village should do next and why.
 */
public class AIAdvice {
    public final TaskType task;
    public final String reason;
    public final long timestamp;

    public AIAdvice(TaskType task, String reason, long timestamp) {
        this.task = task;
        this.reason = reason;
        this.timestamp = timestamp;
    }

    public boolean isStale(int intervalMinutes) {
        return System.currentTimeMillis() - timestamp > intervalMinutes * 60_000L;
    }

    @Override
    public String toString() {
        return task + " (" + reason + ")";
    }
}
