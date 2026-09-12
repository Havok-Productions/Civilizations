package dev.hearth.ai;

import dev.hearth.HearthPlugin;
import dev.hearth.village.Village;

import java.io.File;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One of the Quen mini-agents that run the villagers.
 *
 * <p>Every agent:
 * <ul>
 *   <li>owns a stable subset of villages (village id hash % agent count),</li>
 *   <li>has its own single-thread executor (daemon, {@code quen-agent-N}),</li>
 *   <li>keeps its own persistent memory per village
 *       ({@code plugins/Civilizations/memory/agent-N/<villageId>.json}),</li>
 *   <li>feeds that memory back into its prompt, so its advice improves over time.</li>
 * </ul>
 *
 * <p><b>Folia-safety:</b> {@link #advise} is only called from a village's region
 * task; it merely copies the (read-only) report and submits to the agent's
 * executor. All LLM I/O and memory writes happen on the agent thread — plain
 * Java, no Bukkit world/entity access. The advice callback is marshalled back
 * onto the village center's region via {@code HearthPlugin#runInRegion} before
 * anything may touch village state.
 */
public class VillagerAgent {

    private final int id; // 1-based
    private final int total;
    private final AgentMemory memory;
    private final ExecutorService executor;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile String lastError = "";
    private volatile String lastTask = "-";
    private volatile long lastAdviceAt;
    private volatile int adviceCount;
    private volatile int errorCount;
    private volatile String lastVillager = "-";

    public VillagerAgent(int id, int total, File memoryDir) {
        this.id = id;
        this.total = total;
        this.memory = new AgentMemory(memoryDir);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "quen-agent-" + id);
            t.setDaemon(true);
            return t;
        });
    }

    public int id() {
        return id;
    }

    /**
     * Stable ownership: the same village is always advised by the same agent,
     * so its memory accumulates for that village.
     */
    public boolean owns(Village village, int count) {
        return Math.floorMod(village.getId().hashCode(), Math.max(1, count)) == (id - 1);
    }

    /**
     * Ask this agent for advice. Non-blocking: returns immediately after
     * submitting the work to the agent's executor. The callback is invoked on
     * the village center's region thread (or inline if the region scheduler is
     * unavailable).
     */
    public void advise(HearthPlugin plugin, Village village, Consumer<AIAdvice> callback) {
        if (!plugin.aiEnabled() || !owns(village, total)) {
            callback.accept(null);
            return;
        }
        if (!endpointReady(plugin)) {
            // Local runtime still booting, or external key not configured:
            // silently defer to the local rules (PriorityPolicy) - no log spam.
            callback.accept(null);
            return;
        }
        // Read-only snapshot, taken on the caller's region thread (legal).
        final String report;
        final String learnings;
        try {
            report = ReportBuilder.build(village, village.getWorld().getTime());
            learnings = memory.recentLearnings(village, 10);
        } catch (Throwable t) {
            plugin.getLogger().warning("Quen agent " + id + "/" + total + " could not snapshot village: " + t);
            callback.accept(null);
            return;
        }

        executor.submit(() -> {
            AIAdvice result;
            try {
                String content = ChatCompletions.call(
                                client,
                                plugin.aiEndpointBaseUrl(),
                                plugin.aiEndpointApiKey(),
                                plugin.aiEndpointModel(),
                                plugin.aiTemperature(),
                                plugin.aiMaxTokens(),
                                plugin.aiTimeoutSeconds(),
                                systemPrompt(plugin),
                                userPrompt(learnings, report))
                        .get(Math.max(15, plugin.aiTimeoutSeconds() + 10), TimeUnit.SECONDS);
                result = AdviceParser.parse(content);
            } catch (Exception ex) {
                result = null;
                lastError = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                errorCount++;
                plugin.getLogger().warning("Quen agent " + id + "/" + total + " advice failed: " + lastError);
            }
            final AIAdvice advice = result;
            if (advice != null) {
                try {
                    memory.record(plugin, village, advice);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Quen agent " + id + " memory write failed: " + ex.getMessage());
                }
                adviceCount++;
                lastTask = advice.task.name().toLowerCase(Locale.ROOT);
                lastAdviceAt = System.currentTimeMillis();
                lastVillager = village.getName();
            }
            // Marshal back to the village's region before the callback may run.
            plugin.runInRegion(village.getCenter(), () -> callback.accept(advice));
        });
    }

    /**
     * v1.2: answer one villager-specific question ("I'm stuck, what now?").
     * Uses the same JSON contract as {@link #advise}, so the reply is always
     * an actionable task the local rules can execute.
     *
     * <p>Folia-safety: same contract as {@link #advise} — the callback runs
     * on the village center's region (or inline when not owned / not ready),
     * and must not touch the world.
     */
    public void consultStuck(HearthPlugin plugin, Village village, String question, Consumer<AIAdvice> callback) {
        if (!plugin.aiEnabled() || !owns(village, total) || !endpointReady(plugin)) {
            callback.accept(null);
            return;
        }
        final String report;
        final String learnings;
        final String q;
        try {
            report = ReportBuilder.build(village, village.getWorld().getTime());
            learnings = memory.recentLearnings(village, 5);
            q = question == null ? "A villager is stuck. What should it do next?" : question;
        } catch (Throwable t) {
            plugin.getLogger().warning("Quen agent " + id + "/" + total + " could not snapshot village: " + t);
            callback.accept(null);
            return;
        }

        executor.submit(() -> {
            AIAdvice result;
            try {
                String content = ChatCompletions.call(
                                client,
                                plugin.aiEndpointBaseUrl(),
                                plugin.aiEndpointApiKey(),
                                plugin.aiEndpointModel(),
                                plugin.aiTemperature(),
                                Math.min(plugin.aiMaxTokens(), 120),
                                Math.max(8, plugin.aiTimeoutSeconds() / 2),
                                systemPrompt(plugin) + "\n\n"
                                        + "Sometimes a villager asks one specific question when it is stuck or lost."
                                        + " In that case, answer the question by choosing the single best next task.\n",
                                userPrompt(learnings, report) + "\n\nA villager is stuck and asks: " + q + "\n")
                        .get(Math.max(10, plugin.aiTimeoutSeconds() / 2 + 10), TimeUnit.SECONDS);
                result = AdviceParser.parse(content);
            } catch (Exception ex) {
                result = null;
                lastError = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                errorCount++;
                plugin.getLogger().warning("Quen agent " + id + "/" + total + " stuck-consult failed: " + lastError);
            }
            final AIAdvice advice = result;
            if (advice != null) {
                try {
                    memory.record(plugin, village, advice);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Quen agent " + id + " memory write failed: " + ex.getMessage());
                }
                lastAdviceAt = System.currentTimeMillis();
                lastVillager = village.getName() + " (stuck consult)";
            }
            plugin.runInRegion(village.getCenter(), () -> callback.accept(advice));
        });
    }

    private boolean endpointReady(HearthPlugin plugin) {
        String base = plugin.aiEndpointBaseUrl();
        if (base == null || base.isBlank()) {
            return false;
        }
        if ("external".equalsIgnoreCase(plugin.aiBackend())) {
            String key = plugin.aiEndpointApiKey();
            return key != null && !key.isBlank() && !key.startsWith("sk-put-");
        }
        return true;
    }

    private String systemPrompt(HearthPlugin plugin) {
        return "You are Quen agent #" + id + " of " + total + ", one of the mini-agents of Hearth, "
                + "the AI core inside a Minecraft server that runs a villager village.\n"
                + "You share the village's brain with your sibling agents; when you are asked,\n"
                + "you decide the village's single most important next task.\n\n"
                + "Village charter:\n" + plugin.aiCharter() + "\n\n"
                + "You are given (a) what you have learned about this village from previous rounds\n"
                + "and (b) a fresh JSON report of its current state.\n"
                + "Pick the single most important next task from:\n"
                + "sleep | repair_wall | build_wall | place_chest | lighting | mine | gather | idle\n\n"
                + "Respond ONLY with minified JSON, no markdown, in this exact shape:\n"
                + "{\"task\":\"<task>\",\"reason\":\"one short sentence\"}";
    }

    private String userPrompt(String learnings, String report) {
        StringBuilder sb = new StringBuilder();
        sb.append("What you have learned about this village so far:\n");
        sb.append(learnings == null || learnings.isBlank() ? "(none yet - this is your first look at them)" : learnings);
        sb.append("\n\nFresh village report:\n").append(report).append('\n');
        return sb.toString();
    }

    /**
     * One-line status for /hearth ai status.
     */
    public String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("agent ").append(id).append('/').append(total)
                .append(": ").append(adviceCount).append(" advices, ").append(errorCount).append(" errors");
        if (lastAdviceAt > 0) {
            long mins = Math.max(0, (System.currentTimeMillis() - lastAdviceAt) / 60_000L);
            sb.append(", last: ").append(lastTask)
                    .append(" for ").append(lastVillager)
                    .append(' ').append(mins).append("m ago");
        }
        if (!lastError.isEmpty()) {
            sb.append(", last error: ").append(lastError.length() > 80 ? lastError.substring(0, 80) + "..." : lastError);
        }
        return sb.toString();
    }

    public File memoryDirectory() {
        return memory.directory();
    }

    /**
     * Stop accepting work and interrupt the worker. Safe to call more than once.
     */
    public void shutdown() {
        executor.shutdownNow();
    }
}
