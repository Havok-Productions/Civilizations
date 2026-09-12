package dev.hearth.social;

import java.util.UUID;

/**
 * One villager's "thought" as seen by a neighbor during a gossip scan:
 * what it is doing, what it is carrying, and what it currently needs.
 *
 * <p>Immutable on purpose: a villager only ever <em>reads</em> its peers'
 * (volatile) brain fields and stores a copy of that snapshot here in its
 * <em>own</em> cache. That keeps the cross-region contract simple on Folia:
 * read peers, write self, never write a neighbor's state.
 */
public final class Thought {

    public final UUID villager;
    /** Lower-cased task name ("wall", "mine", "gather", "idle", ...) or "" . */
    public final String task;
    /** Lower-cased state name ("idle", "travel", "work", "sleep", "flee"). */
    public final String state;
    /** What is being carried, e.g. "stone:6", or "" when empty. */
    public final String carrying;
    /** Free-form note, e.g. the material a need is about; "" when none. */
    public final String note;
    /** When this villager last observed the peer (System.currentTimeMillis()). */
    public final long seenAt;

    public Thought(UUID villager, String task, String state, String carrying, String note, long seenAt) {
        this.villager = villager;
        this.task = task == null ? "" : task;
        this.state = state == null ? "" : state;
        this.carrying = carrying == null ? "" : carrying;
        this.note = note == null ? "" : note;
        this.seenAt = seenAt;
    }

    /**
     * True if this thought is older than {@code ttlMs}.
     */
    public boolean isStale(long now, long ttlMs) {
        return now - seenAt > ttlMs;
    }

    /**
     * One line, for status output / prompts.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(task.isEmpty() ? "idle" : task).append(']');
        if (!carrying.isEmpty()) {
            sb.append(" carrying ").append(carrying);
        }
        if (!note.isEmpty()) {
            sb.append(" (").append(note).append(')');
        }
        return sb.toString();
    }
}
