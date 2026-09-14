package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.entity.Villager;

/** Reuse completed items and intermediate ingredients already present in community storage. */
public final class ChestSupplies {
  private ChestSupplies() {}

  public static boolean obtain(
      CivilizationsPlugin plugin,
      Villager actor,
      Settlement village,
      WorkerNavigation navigation,
      String output,
      Map<String, Integer> inventory,
      boolean table,
      Pos at,
      long now) {
    int demand = village.placementDemand(actor.getUniqueId().toString(), output, now);
    Pos chest =
        village.supplyChest(
            at,
            stock ->
                !plugin.recipes().withdrawal(output, inventory, stock, table, demand).isEmpty(),
            now);
    if (chest == null || village.knowledge().blocked("route:" + chest.key(), now)) return false;
    Map<String, Integer> wanted =
        plugin.recipes().withdrawal(output, inventory, village.stock(chest), table, demand);
    if (wanted.isEmpty()) return false;
    Location p = new Location(actor.getWorld(), chest.x(), chest.y(), chest.z());
    if (at.distance2(chest) > 12 || !Bukkit.isOwnedByCurrentRegion(p, 1)) {
      navigation.walk(chest, now);
      return true;
    }
    if (!(p.getBlock().getState() instanceof Chest storage)) {
      village.removeChest(chest);
      return false;
    }
    Map<String, Integer> beforeChest = InventoryOps.summary(storage.getInventory());
    Map<String, Integer> beforeActor = InventoryOps.summary(actor.getInventory());
    wanted = plugin.recipes().withdrawal(output, beforeActor, beforeChest, table, demand);
    int total = 0;
    for (var e : wanted.entrySet())
      total +=
          InventoryOps.transfer(
              storage.getInventory(),
              actor.getInventory(),
              Set.of(Material.valueOf(e.getKey())),
              e.getValue());
    village.stock(chest, InventoryOps.summary(storage.getInventory()), now);
    if (total > 0) {
      TransferReceipts.record(
          plugin,
          village,
          actor.getUniqueId().toString(),
          "withdraw",
          "chest:" + chest.key(),
          actor.getUniqueId().toString(),
          beforeChest,
          storage.getInventory(),
          beforeActor,
          actor.getInventory());
      village.remember(
          actor.getUniqueId().toString(),
          "Withdrew "
              + total
              + " stored items for "
              + output
              + ": "
              + TransferReceipts.added(beforeActor, actor.getInventory()),
          true);
    }
    return total > 0;
  }
}
