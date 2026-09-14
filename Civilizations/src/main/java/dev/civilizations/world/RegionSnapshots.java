package dev.civilizations.world;

import dev.civilizations.core.Pos;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.plugin.Plugin;

/** No blocking waits and no forced chunk loads. Snapshot acquisition owns each chunk. */
public final class RegionSnapshots {
  private final Plugin plugin;
  private final Executor executor;
  private volatile dev.coreai.TerrainRuleBook rules;

  public void rules(dev.coreai.TerrainRuleBook rules) {
    this.rules = rules;
  }

  public RegionSnapshots(Plugin plugin, Executor executor) {
    this.plugin = plugin;
    this.executor = executor;
  }

  public CompletableFuture<Terrain> capture(World world, Pos center, int radius) {
    Map<Long, CompletableFuture<ChunkSnapshot>> calls = new HashMap<>();
    for (int x = (center.x() - radius) >> 4; x <= (center.x() + radius) >> 4; x++)
      for (int z = (center.z() - radius) >> 4; z <= (center.z() + radius) >> 4; z++) {
        int cx = x, cz = z;
        CompletableFuture<ChunkSnapshot> f = new CompletableFuture<>();
        calls.put(key(x, z), f);
        try {
          Bukkit.getRegionScheduler()
              .execute(
                  plugin,
                  world,
                  cx,
                  cz,
                  () -> {
                    try {
                      f.complete(
                          world.isChunkLoaded(cx, cz)
                              ? world.getChunkAt(cx, cz).getChunkSnapshot(true, false, false)
                              : null);
                    } catch (Exception e) {
                      f.complete(null);
                    }
                  });
        } catch (Exception e) {
          f.complete(null);
        }
        f.completeOnTimeout(null, 4, TimeUnit.SECONDS);
      }
    return CompletableFuture.allOf(calls.values().toArray(CompletableFuture[]::new))
        .thenApplyAsync(
            ignored -> {
              Map<Long, ChunkSnapshot> chunks = new HashMap<>();
              calls.forEach(
                  (k, f) -> {
                    ChunkSnapshot snapshot = f.getNow(null);
                    if (snapshot != null) chunks.put(k, snapshot);
                  });
              return new Captured(
                  Map.copyOf(chunks),
                  world.getMinHeight(),
                  world.getMaxHeight(),
                  rules == null ? Map.of() : rules.view(""));
            },
            executor);
  }

  private static long key(int x, int z) {
    return ((long) x << 32) ^ (z & 0xffffffffL);
  }

  private record Captured(
      Map<Long, ChunkSnapshot> chunks,
      int min,
      int max,
      Map<String, dev.coreai.TerrainRuleBook.Rule> learned)
      implements Terrain {
    public boolean clear(Pos p) {
      if (Terrain.super.clear(p)) return true;
      String type = type(p), state = blockData(p);
      var rule = learned.get(dev.coreai.TerrainRuleBook.key(type, state));
      return rule != null
          && rule.facts().removable()
          && !rule.facts().fluid()
          && !BlockObservation.dangerous(type, state);
    }

    public String blockData(Pos p) {
      ChunkSnapshot c = chunks.get(key(p.x() >> 4, p.z() >> 4));
      return c == null || p.y() < min || p.y() >= max
          ? null
          : c.getBlockData(p.x() & 15, p.y(), p.z() & 15).getAsString();
    }

    public boolean matureWheat(Pos p) {
      ChunkSnapshot c = chunks.get(key(p.x() >> 4, p.z() >> 4));
      return c != null
          && p.y() >= min
          && p.y() < max
          && type(p).equals("WHEAT")
          && c.getBlockData(p.x() & 15, p.y(), p.z() & 15)
              instanceof org.bukkit.block.data.Ageable age
          && age.getAge() == age.getMaximumAge();
    }

    public boolean bedFoot(Pos p) {
      ChunkSnapshot c = chunks.get(key(p.x() >> 4, p.z() >> 4));
      return c != null
          && p.y() >= min
          && p.y() < max
          && c.getBlockData(p.x() & 15, p.y(), p.z() & 15)
              instanceof org.bukkit.block.data.type.Bed b
          && b.getPart() == org.bukkit.block.data.type.Bed.Part.FOOT;
    }

    public boolean available(int x, int z) {
      return chunks.containsKey(key(x >> 4, z >> 4));
    }

    public int height(int x, int z) {
      ChunkSnapshot c = chunks.get(key(x >> 4, z >> 4));
      return c == null ? min : c.getHighestBlockYAt(x & 15, z & 15);
    }

    public String type(Pos p) {
      ChunkSnapshot c = chunks.get(key(p.x() >> 4, p.z() >> 4));
      return c == null || p.y() < min || p.y() >= max
          ? "UNKNOWN"
          : c.getBlockType(p.x() & 15, p.y(), p.z() & 15).name();
    }
  }
}
