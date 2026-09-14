package dev.civilizations.world;

import dev.civilizations.core.ToolRecipes;
import java.util.Map;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.Damageable;

/** Selects an actual adequate pickaxe, and consumes durability only after a block is removed. */
public final class MiningTools {
  private MiningTools() {}

  public static boolean has(Inventory inv, String block) {
    return slot(inv, ToolRecipes.required(block)) >= 0 || ToolRecipes.required(block) == 0;
  }

  private static int slot(Inventory inv, int needed) {
    for (int i = 0; i < inv.getSize(); i++) {
      ItemStack item = inv.getItem(i);
      if (item != null
          && ToolRecipes.tier(Map.of(item.getType().name(), 1)) >= needed
          && item.getType().name().endsWith("_PICKAXE")) return i;
    }
    return -1;
  }

  public static void used(Inventory inv, String block) {
    int tier = ToolRecipes.required(block);
    if (tier == 0) return;
    int slot = slot(inv, tier);
    if (slot < 0) throw new IllegalStateException("Mining tool missing");
    ItemStack item = inv.getItem(slot).clone();
    if (item.getItemMeta() instanceof Damageable damage && !damage.isUnbreakable()) {
      int next = damage.getDamage() + 1;
      if (next >= item.getType().getMaxDurability()) inv.setItem(slot, null);
      else {
        damage.setDamage(next);
        item.setItemMeta(damage);
        inv.setItem(slot, item);
      }
    }
  }
}
