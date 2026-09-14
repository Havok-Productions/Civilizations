package dev.civilizations.world;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.Plugin;

/** Enumerates loaded chunk coordinates globally; reads entities only on their owning regions. */
public final class LoadedVillagerDiscovery implements AutoCloseable {
  private record ChunkKey(UUID world, int x, int z) {}

  private final Plugin plugin;
  private final Consumer<Villager> discovered;
  private final ConcurrentLinkedQueue<ChunkKey> queue = new ConcurrentLinkedQueue<>();
  private final Set<ChunkKey> pending = ConcurrentHashMap.newKeySet();
  private final int chunksPerTick;
  private volatile boolean closed;

  public LoadedVillagerDiscovery(Plugin plugin, int chunksPerTick, Consumer<Villager> discovered) {
    this.plugin = plugin;
    this.chunksPerTick = chunksPerTick;
    this.discovered = discovered;
  }

  public void start() {
    Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> drain(), 1, 1);
  }

  /** Folia's getLoadedChunks() enumerates the concurrent loaded-chunk coordinate registry. */
  public void refresh() {
    if (closed) return;
    for (World world : Bukkit.getWorlds()) {
      for (Chunk chunk : world.getLoadedChunks()) {
        ChunkKey key = new ChunkKey(world.getUID(), chunk.getX(), chunk.getZ());
        if (pending.add(key)) queue.add(key);
      }
    }
  }

  private void drain() {
    if (closed) return;
    for (int i = 0; i < chunksPerTick; i++) {
      ChunkKey key = queue.poll();
      if (key == null) return;
      World world = Bukkit.getWorld(key.world());
      if (world == null) {
        pending.remove(key);
        continue;
      }
      try {
        Bukkit.getRegionScheduler()
            .execute(
                plugin,
                world,
                key.x(),
                key.z(),
                () -> {
                  try {
                    if (closed || !world.isChunkLoaded(key.x(), key.z())) return;
                    Chunk chunk = world.getChunkAt(key.x(), key.z());
                    // getEntities() may load entity data. Never call it until that data is loaded
                    // already.
                    if (!chunk.isEntitiesLoaded()) return;
                    for (Entity entity : chunk.getEntities())
                      if (entity instanceof Villager villager) {
                        villager
                            .getScheduler()
                            .run(
                                plugin,
                                task -> {
                                  if (!closed) discovered.accept(villager);
                                },
                                () -> {});
                      }
                  } finally {
                    pending.remove(key);
                  }
                });
      } catch (RuntimeException e) {
        pending.remove(key);
        if (!closed)
          plugin.getLogger().warning("Loaded-chunk discovery deferred: " + e.getMessage());
      }
    }
  }

  public int pendingChunks() {
    return pending.size();
  }

  @Override
  public void close() {
    closed = true;
    queue.clear();
    pending.clear();
  }
}
