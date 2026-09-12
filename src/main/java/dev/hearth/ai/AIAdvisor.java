package dev.hearth.ai;

import dev.hearth.HearthPlugin;
import dev.hearth.village.Village;

import java.util.function.Consumer;

/**
 * Strategy advisor for a village. Implementations may be local heuristics
 * or a remote LLM. Results are applied on the plugin scheduler thread.
 */
public interface AIAdvisor {

    /**
     * Ask the advisor what the village should do next.
     *
     * @param plugin the plugin (for config + scheduler)
     * @param village the village to advise on
     * @param callback receives the advice (may be null on failure); runs on the scheduler thread
     */
    void advise(HearthPlugin plugin, Village village, Consumer<AIAdvice> callback);
}
