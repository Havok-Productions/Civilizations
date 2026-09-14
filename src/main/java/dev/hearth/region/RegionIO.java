package dev.hearth.region;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Folia-safe world reads.
 *
 * <p>On Folia, a block read is only legal on the region thread that owns the
 * block's chunk. Reading from any other thread (a brain on a neighboring
 * region, the global region thread during discovery) throws
 * "Thread failed main thread check: Cannot retrieve chunk asynchronously".
 *
 * <p>{@link #inChunk} / {@link #inChunks} route reads onto the owning region
 * thread:
 * <ul>
 *   <li>if the <em>current</em> thread already owns the chunk, the read runs
 *       inline (zero latency — the common case for brains reading their own
 *       village area);</li>
 *   <li>otherwise the read is queued onto the owning region via
 *       {@code RegionScheduler#execute} and the caller waits briefly for the
 *       result; if the region cannot deliver it in time (server shutdown,
 *       extreme lag, unloaded chunk) the supplied fallback is returned instead
 *       of a crash.</li>
 * </ul>
 *
 * <p><b>Why the wait is double-bounded (v1.3.2 watchdog fix):</b> a Folia
 * region thread that blocks on another region for several seconds trips Folia's
 * tick-region watchdog ("has not responded in 5.9s"). Two mechanisms keep that
 * from ever happening:
 * <ol>
 *   <li><em>One wait per batch.</em> {@link #inChunks} dispatches every chunk's
 *       read first (all in flight concurrently) and then performs a single
 *       bounded wait for all of them — a scan of 40 chunks costs one wait, not
 *       40 sequential waits.</li>
 *   <li><em>Per-thread rolling budget.</em> every real wait is additionally
 *       capped by a circuit breaker: at most {@value #BUDGET_MS} ms of
 *       cross-region blocking per thread in any {@value #WINDOW_MS} ms window.
 *       Once the budget is spent, further reads in that window fast-fail to
 *       their fallback instead of blocking (the caller retries on a later
 *       tick, which is the designed "chunk unavailable" path).</li>
 * </ol>
 * Both bounds sit far under Folia's ~6 s watchdog threshold, so no region — or
 * global — thread can stall long enough to be killed by the watchdog.
 *
 * <p>Batching rule: group every read that belongs to one chunk into a single
 * {@link #inChunks} call so a scan pays one cross-region round trip per chunk,
 * not one per block.
 *
 * <p>On non-Folia (Paper/Spigot) servers the ownership check is skipped and
 * every read runs inline — zero overhead, identical behavior to before.
 *
 * <p>The read tasks are pure reads, so they can never deadlock a region: the
 * owning region services each one on its next tick regardless of what the
 * caller is doing, and the bounded waits guarantee the caller is never stuck.
 */
public final class RegionIO {

    /**
     * Per-read wait cap. A healthy server delivers a queued read on the owning
     * region's next tick (&lt; 50 ms); 500 ms is 10 ticks of grace.
     */
    private static final long SINGLE_READ_MS = 500L;

    /**
     * Total wait budget for a batch of reads ({@link #inChunks}). All reads are
     * in flight concurrently, so one healthy tick serves them all; the budget
     * is only a safety cap for a congested server.
     */
    private static final long BATCH_WAIT_MS = 1500L;

    /** Rolling-window size for the per-thread cross-region blocking budget. */
    private static final long WINDOW_MS = 3000L;

    /**
     * Maximum cross-region blocking per thread per rolling window (the circuit
     * breaker). Well under Folia's ~6 s watchdog threshold, so even a caller
     * that loops many reads on a region thread can never stall the region.
     */
    private static final long BUDGET_MS = 1200L;

    private static final boolean FOLIA = detectFolia();

    /** Per-thread rolling spend of cross-region blocking: {@code [windowStartMs, usedMs]}. */
    private static final ThreadLocal<long[]> SPEND = ThreadLocal.withInitial(() -> new long[]{0L, 0L});

    private RegionIO() {
    }

    private static boolean detectFolia() {
        try {
            // Present only on Folia builds (the region scheduler API).
            Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    /** Stable key for a chunk column (x,z) in chunk coordinates. */
    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    /** Decode a {@link #chunkKey} chunk X (chunk coordinates). */
    public static int keyChunkX(long key) {
        return (int) (key >> 32);
    }

    /** Decode a {@link #chunkKey} chunk Z (chunk coordinates). */
    public static int keyChunkZ(long key) {
        return (int) key;
    }

    /**
     * Run {@code read} on the region thread that owns the chunk containing
     * block column ({@code x}, {@code z}) and return its result.
     *
     * <p>This is the single-read convenience wrapper; for scans spanning
     * several chunks prefer {@link #inChunks}, which makes one bounded wait
     * for the whole batch instead of one wait per chunk.
     *
     * @param p        plugin (attributed to the queued task)
     * @param world    the world
     * @param x        block X of any block in the target chunk
     * @param z        block Z of any block in the target chunk
     * @param read     the read to perform (runs on the owning region; pure read)
     * @param fallback returned when the read cannot be delivered (never null-
     *                 throwing to the caller)
     */
    public static <T> T inChunk(Plugin p, World world, int x, int z, Supplier<T> read, T fallback) {
        if (world == null) {
            return fallback;
        }
        Map<Long, T> out = inChunks(p, world,
                Map.of(chunkKey(x >> 4, z >> 4), read), fallback, SINGLE_READ_MS);
        return out.getOrDefault(chunkKey(x >> 4, z >> 4), fallback);
    }

    /**
     * Run one read per chunk key, each on the region thread that owns the
     * chunk, and return the results keyed by the same chunk keys.
     *
     * <p>All reads are dispatched first (so every owning region services its
     * read on its next tick, concurrently), and the caller then performs a
     * <em>single</em> bounded wait for the whole batch. Missing results (a
     * region could not deliver in time) are filled with {@code fallback}.
     *
     * <p>All keys must be {@link #chunkKey} values in <em>chunk</em>
     * coordinates and must belong to {@code world}; callers keep one world per
     * batch (every scan in this plugin does).
     *
     * @param p        plugin (attributed to the queued tasks)
     * @param world    the world all chunks belong to
     * @param reads    chunk key -&gt; read to run on that chunk's owning region
     * @param fallback returned for any chunk whose read cannot be delivered
     * @param waitMs   total wait budget for the batch (the per-read cap for
     *                 {@link #inChunk}); further reduced by the circuit breaker
     * @return one entry per input key (never null values unless the fallback is null)
     */
    public static <T> Map<Long, T> inChunks(Plugin p, World world, Map<Long, Supplier<T>> reads, T fallback, long waitMs) {
        Map<Long, T> out = new HashMap<>();
        if (world == null || reads == null || reads.isEmpty()) {
            return out;
        }
        if (!FOLIA) {
            // No region ownership on Paper/Spigot: every read runs inline.
            for (Map.Entry<Long, Supplier<T>> e : reads.entrySet()) {
                out.put(e.getKey(), runInline(e.getValue(), fallback));
            }
            return out;
        }
        // 1. Dispatch: inline where we own the chunk, otherwise queue onto the
        //    owning region. All reads are in flight before anyone waits.
        Map<Long, CompletableFuture<T>> pending = new HashMap<>();
        for (Map.Entry<Long, Supplier<T>> e : reads.entrySet()) {
            long key = e.getKey();
            int chunkX = keyChunkX(key);
            int chunkZ = keyChunkZ(key);
            if (ownsCurrentRegion(world, chunkX, chunkZ)) {
                out.put(key, runInline(e.getValue(), fallback));
                continue;
            }
            CompletableFuture<T> result = new CompletableFuture<>();
            try {
                Bukkit.getRegionScheduler().execute(p, world, chunkX, chunkZ, () -> {
                    try {
                        result.complete(e.getValue().get());
                    } catch (Throwable t) {
                        result.complete(fallback);
                    }
                });
            } catch (Throwable t) {
                // Scheduler unavailable (plugin disabling, server shutting
                // down): best-effort inline, then the fallback.
                out.put(key, runInline(e.getValue(), fallback));
                continue;
            }
            pending.put(key, result);
        }
        // 2. One bounded wait for everything still in flight. The circuit
        //    breaker shrinks the wait as the thread's window budget is spent,
        //    and once it is exhausted reads fast-fail to the fallback.
        for (Map.Entry<Long, CompletableFuture<T>> e : pending.entrySet()) {
            CompletableFuture<T> f = e.getValue();
            if (f.isDone()) {
                out.put(e.getKey(), f.getNow(fallback));
                continue;
            }
            long allow = remainingBudget(waitMs);
            long t0 = System.nanoTime();
            T value = fallback;
            try {
                if (allow > 0L) {
                    value = f.get(allow, TimeUnit.MILLISECONDS);
                } else {
                    value = f.getNow(fallback);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                value = f.getNow(fallback);
            }
            out.put(e.getKey(), value);
            charge((System.nanoTime() - t0) / 1_000_000L);
        }
        return out;
    }

    /**
     * {@link #inChunks(Plugin, World, Map, Object, long)} with the default
     * batch wait budget ({@value #BATCH_WAIT_MS} ms).
     */
    public static <T> Map<Long, T> inChunks(Plugin p, World world, Map<Long, Supplier<T>> reads, T fallback) {
        return inChunks(p, world, reads, fallback, BATCH_WAIT_MS);
    }

    /**
     * Circuit breaker: how much cross-region blocking this thread may still do
     * in the current rolling window, capped by {@code capMs}. Returns 0 once
     * the window budget is spent (callers then fast-fail to their fallback).
     */
    private static long remainingBudget(long capMs) {
        long now = System.currentTimeMillis();
        long[] b = SPEND.get();
        if (now - b[0] >= WINDOW_MS) {
            b[0] = now;
            b[1] = 0L;
        }
        long used = b[1];
        if (used >= BUDGET_MS) {
            return 0L;
        }
        return Math.min(capMs, BUDGET_MS - used);
    }

    /** Record {@code ms} of cross-region blocking against this thread's window. */
    private static void charge(long ms) {
        if (ms <= 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        long[] b = SPEND.get();
        if (now - b[0] >= WINDOW_MS) {
            b[0] = now;
            b[1] = 0L;
        }
        b[1] += ms;
    }

    private static <T> T runInline(Supplier<T> read, T fallback) {
        try {
            return read.get();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static boolean ownsCurrentRegion(World world, int chunkX, int chunkZ) {
        try {
            return Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Read the block type at {@code loc} in a Folia-safe way.
     *
     * @param fallback the material to report when the read cannot be delivered.
     *                 Pick it to steer the caller down its safe branch
     *                 (e.g. {@code Material.AIR} for "proceed, the write path
     *                 re-validates in-region" checks).
     */
    public static Material blockType(Plugin p, Location at, Material fallback) {
        if (at == null || at.getWorld() == null) {
            return fallback;
        }
        return inChunk(p, at.getWorld(), at.getBlockX(), at.getBlockZ(),
                () -> at.getBlock().getType(), fallback);
    }
}
