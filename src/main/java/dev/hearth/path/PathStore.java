package dev.hearth.path;

import dev.hearth.HearthPlugin;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages active path searches per villager.
 *
 * <p>Folia note: pathfinding is pure block <em>reading</em>, which is safe from any
 * region thread. Because each brain now ticks on its own entity region thread, this
 * store is accessed concurrently and therefore uses concurrent maps. Each brain steps
 * only its own request ({@link #tickFor(HearthPlugin, Request)}), keeping the work
 * bounded per villager.
 */
public class PathStore {

    public static final class Request {
        public final UUID id;
        public final World world;
        public final PathSearch search;
        public long createdAt;
        public long lastProgress;
        public final UUID owner;

        Request(UUID id, World world, PathSearch search, UUID owner) {
            this.id = id;
            this.world = world;
            this.search = search;
            this.owner = owner;
            this.createdAt = System.currentTimeMillis();
            this.lastProgress = System.currentTimeMillis();
        }
    }

    private final Map<UUID, Request> pending = new ConcurrentHashMap<>();
    private final Map<UUID, PathResult> results = new ConcurrentHashMap<>();
    private final HearthPlugin plugin;

    public PathStore(HearthPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Start (or replace) a path request for the given owner.
     */
    public Request request(World world, Location start, Location goal) {
        Request req = new Request(UUID.randomUUID(), world,
                new PathSearch(world,
                        start.getBlockX(), start.getBlockY(), start.getBlockZ(),
                        goal.getBlockX(), goal.getBlockY(), goal.getBlockZ(),
                        plugin.maxPathRadius(), plugin.maxPathDepth()),
                null);
        pending.put(req.id, req);
        results.remove(req.id);
        return req;
    }

    /**
     * Cancel a request (e.g. target changed or villager aborted).
     */
    public void cancel(Request req) {
        if (req != null) {
            pending.remove(req.id);
        }
    }

    /**
     * Advance a single request using a bounded per-tick budget.
     * Safe to call concurrently from multiple region threads (each with its own request).
     */
    public void tickFor(HearthPlugin plugin, Request req) {
        if (req == null) {
            return;
        }
        if (pending.containsKey(req.id)) {
            int budget = Math.max(200, Math.min(plugin.pathBudgetPerTick(), 4000));
            PathSearch.Status s = req.search.step(budget);
            if (s == PathSearch.Status.DONE) {
                results.put(req.id, new PathResult(req.search.path(), req.search.expansions()));
                pending.remove(req.id);
            } else if (s == PathSearch.Status.FAILED) {
                results.put(req.id, new PathResult(List.of(), req.search.expansions()));
                pending.remove(req.id);
            }
        }
        // Expire stale results (older than 5 minutes) to avoid unbounded growth.
        long now = System.currentTimeMillis();
        results.values().removeIf(r -> now - r.createdAt > 300_000L);
    }

    /**
     * Poll for a finished result. Returns null while the search is still running.
     * An empty result list means "no path found".
     */
    public PathResult poll(Request req) {
        if (req == null) {
            return null;
        }
        return results.get(req.id);
    }

    public boolean isPending(Request req) {
        return req != null && pending.containsKey(req.id);
    }

    public int pendingCount() {
        return pending.size();
    }
}
