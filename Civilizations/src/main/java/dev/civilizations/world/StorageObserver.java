package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.*;
import org.bukkit.block.Chest;

/** Refreshes loaded community storage on its owning region, independently of worker proximity. */
public final class StorageObserver {
  private final CivilizationsPlugin plugin;
  private final Set<String> pending = ConcurrentHashMap.newKeySet();
  private final Map<String, String> last = new ConcurrentHashMap<>();

  public StorageObserver(CivilizationsPlugin plugin) {
    this.plugin = plugin;
  }

  public void refresh(Settlement village, World world) {
    for (Pos p : village.chests()) refresh(village, world, p);
  }

  private void refresh(Settlement village, World world, Pos p) {
    String key = village.id() + ":" + p.key();
    if (village.retired() || !pending.add(key)) return;
    Bukkit.getRegionScheduler()
        .execute(
            plugin,
            new Location(world, p.x(), p.y(), p.z()),
            () ->
                plugin
                    .connections()
                    .read(
                        () -> {
                          try {
                            if (village.retired() || !village.chests().contains(p)) return;
                            Location at = new Location(world, p.x(), p.y(), p.z());
                            if (!world.isChunkLoaded(p.x() >> 4, p.z() >> 4)
                                || !Bukkit.isOwnedByCurrentRegion(at, 1)) {
                              report(village, p, "unobserved_region", Map.of());
                              return;
                            }
                            if (!(at.getBlock().getState() instanceof Chest chest)) {
                              village.removeChest(p);
                              report(village, p, "missing", Map.of());
                              return;
                            }
                            // Owning the surrounding chunks makes both halves of a double chest
                            // safe to
                            // inspect.
                            if (chest.getInventory()
                                instanceof org.bukkit.inventory.DoubleChestInventory pair) {
                              Location l = pair.getLeftSide().getLocation(),
                                  r = pair.getRightSide().getLocation();
                              if (l != null && r != null) {
                                Pos left = new Pos(l.getBlockX(), l.getBlockY(), l.getBlockZ());
                                Pos right = new Pos(r.getBlockX(), r.getBlockY(), r.getBlockZ());
                                if (village.chests().contains(left)
                                    && village.chests().contains(right)) {
                                  Pos duplicate =
                                      left.key().compareTo(right.key()) < 0 ? right : left;
                                  village.removeChest(duplicate);
                                  if (p.equals(duplicate)) return;
                                }
                              }
                            }
                            Map<String, Integer> inventory =
                                InventoryOps.summary(chest.getInventory());
                            village.stock(p, inventory, System.currentTimeMillis());
                            village
                                .storageCapacity()
                                .observe(
                                    p,
                                    chest.getInventory().firstEmpty() >= 0,
                                    InventoryOps.partialStackTypes(chest.getInventory()),
                                    System.currentTimeMillis());
                            for (String material : inventory.keySet()) {
                              village.knowledge().clear("resource:" + material);
                              if (material.endsWith("_LOG"))
                                village.knowledge().clear("resource:LOG");
                            }
                            report(village, p, "observed", inventory);
                          } finally {
                            pending.remove(key);
                          }
                        }));
  }

  private void report(Settlement village, Pos p, String status, Map<String, Integer> items) {
    String state = p.key() + status + new TreeMap<>(items);
    if (state.equals(last.put(village.id() + ":" + p.key(), state))) return;
    plugin.debug(
        village.id(),
        "",
        "storage_observation",
        Map.of("position", p, "status", status, "items", items));
  }
}
