package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.Settlement;
import java.util.*;
import org.bukkit.inventory.Inventory;

/** Evidence emitted only after actual inventory mutation, with independently inspectable deltas. */
public final class TransferReceipts {
  private TransferReceipts() {}

  public static Map<String, Integer> added(Map<String, Integer> before, Inventory after) {
    Map<String, Integer> delta = new TreeMap<>();
    InventoryOps.summary(after)
        .forEach(
            (m, n) -> {
              int moved = n - before.getOrDefault(m, 0);
              if (moved > 0) delta.put(m, moved);
            });
    return Map.copyOf(delta);
  }

  public static void record(
      CivilizationsPlugin plugin,
      Settlement village,
      String worker,
      String action,
      String source,
      String destination,
      Map<String, Integer> beforeSource,
      Inventory afterSource,
      Map<String, Integer> beforeDestination,
      Inventory afterDestination) {
    plugin.debug(
        village.id(),
        worker,
        "inventory_transfer",
        Map.of(
            "receipt",
            UUID.randomUUID().toString(),
            "action",
            action,
            "source",
            source,
            "destination",
            destination,
            "source_before",
            beforeSource,
            "source_after",
            InventoryOps.summary(afterSource),
            "destination_before",
            beforeDestination,
            "destination_after",
            InventoryOps.summary(afterDestination)));
  }
}
