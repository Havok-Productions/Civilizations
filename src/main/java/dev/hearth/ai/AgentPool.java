package dev.hearth.ai;

import dev.hearth.HearthPlugin;
import dev.hearth.village.Village;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pool of Quen mini-agents, and the plugin's {@link AIAdvisor}.
 *
 * <p>Routing is stable: a village's UUID hash decides which mini-agent owns it,
 * so each village is always advised by the same agent and that agent's memory
 * file accumulates knowledge about exactly that village.
 *
 * <p>Folia-safety: this class only does bookkeeping and delegates to the
 * agents; see {@link VillagerAgent} for the threading contract.
 */
public class AgentPool implements AIAdvisor {

    private final HearthPlugin plugin;
    private final List<VillagerAgent> agents = new ArrayList<>();

    public AgentPool(HearthPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * (Re)build the pool from the current {@code ai.agents-count} setting.
     * Memory files survive a rebuild — only the executors are recycled.
     */
    public synchronized void refresh() {
        shutdown();
        int count = Math.max(1, plugin.aiAgentsCount());
        for (int i = 1; i <= count; i++) {
            File dir = new File(plugin.getDataFolder(), "memory/agent-" + i);
            agents.add(new VillagerAgent(i, count, dir));
        }
        plugin.getLogger().info("Quen agent pool ready: " + count + " mini-agent(s), memory under "
                + new File(plugin.getDataFolder(), "memory"));
    }

    @Override
    public void advise(HearthPlugin plugin, Village village, Consumer<AIAdvice> callback) {
        List<VillagerAgent> snapshot;
        synchronized (this) {
            if (agents.isEmpty()) {
                callback.accept(null);
                return;
            }
            snapshot = List.copyOf(agents);
        }
        int idx = Math.floorMod(village.getId().hashCode(), snapshot.size());
        snapshot.get(idx).advise(plugin, village, callback);
    }

    public synchronized int count() {
        return agents.size();
    }

    public synchronized List<VillagerAgent> agents() {
        return List.copyOf(agents);
    }

    public synchronized void shutdown() {
        for (VillagerAgent agent : agents) {
            agent.shutdown();
        }
        agents.clear();
    }
}
