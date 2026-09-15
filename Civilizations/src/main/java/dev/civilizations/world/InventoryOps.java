package dev.civilizations.world;

import dev.civilizations.core.RecipeCatalog;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.inventory.*;

/** Entity/chest inventory is only touched when both are owned by the current region. */
public final class InventoryOps {
  public static java.util.Set<String> partialStackTypes(org.bukkit.inventory.Inventory inventory) {
    java.util.Set<String> result = new java.util.HashSet<>();
    for (var item : inventory.getStorageContents())
      if (item != null
          && !item.getType().isAir()
          && item.getAmount() < Math.min(item.getMaxStackSize(), inventory.getMaxStackSize()))
        result.add(item.getType().name());
    return java.util.Set.copyOf(result);
  }

  private InventoryOps() {}

  public static boolean canFit(Inventory inventory, Collection<ItemStack> incoming) {
    ItemStack[] slots =
        Arrays.stream(inventory.getStorageContents())
            .map(s -> s == null ? null : s.clone())
            .toArray(ItemStack[]::new);
    return canFitSlots(slots, incoming);
  }

  public static boolean canExchange(
      Inventory inventory, Map<String, Integer> cost, Collection<ItemStack> output) {
    Map<String, Integer> counts = summary(inventory);
    if (cost.entrySet().stream().anyMatch(e -> counts.getOrDefault(e.getKey(), 0) < e.getValue()))
      return false;
    ItemStack[] slots =
        Arrays.stream(inventory.getStorageContents())
            .map(i -> i == null ? null : i.clone())
            .toArray(ItemStack[]::new);
    cost.forEach(
        (material, count) -> {
          int left = count;
          for (int i = 0; i < slots.length && left > 0; i++) {
            ItemStack item = slots[i];
            if (item == null || !item.getType().name().equals(material)) continue;
            int take = Math.min(left, item.getAmount());
            left -= take;
            if (take == item.getAmount()) slots[i] = null;
            else item.setAmount(item.getAmount() - take);
          }
        });
    return canFitSlots(slots, output);
  }

  private static boolean canFitSlots(ItemStack[] slots, Collection<ItemStack> incoming) {
    for (ItemStack item : incoming) {
      int left = item.getAmount();
      for (ItemStack slot : slots)
        if (slot != null && slot.isSimilar(item)) {
          int n = Math.min(left, Math.max(0, slot.getMaxStackSize() - slot.getAmount()));
          slot.setAmount(slot.getAmount() + n);
          left -= n;
        }
      for (int i = 0; i < slots.length && left > 0; i++)
        if (slots[i] == null || slots[i].getType().isAir()) {
          int n = Math.min(left, item.getMaxStackSize());
          slots[i] = item.clone();
          slots[i].setAmount(n);
          left -= n;
        }
      if (left > 0) return false;
    }
    return true;
  }

  public static boolean canCraft(
      Inventory inventory, Material output, Map<Material, Integer> cost) {
    if (!has(inventory, cost)) return false;
    ItemStack[] slots =
        Arrays.stream(inventory.getStorageContents())
            .map(s -> s == null ? null : s.clone())
            .toArray(ItemStack[]::new);
    cost.forEach(
        (m, n) -> {
          int left = n;
          for (int i = 0; i < slots.length && left > 0; i++) {
            ItemStack item = slots[i];
            if (item == null || item.getType() != m) continue;
            int take = Math.min(left, item.getAmount());
            left -= take;
            if (take == item.getAmount()) slots[i] = null;
            else item.setAmount(item.getAmount() - take);
          }
        });
    Map<String, Integer> named = new LinkedHashMap<>();
    cost.forEach((m, n) -> named.put(m.name(), n));
    return canFitSlots(
        slots,
        RecipeCatalog.leftovers(output.name(), named).entrySet().stream()
            .map(e -> new ItemStack(Material.valueOf(e.getKey()), e.getValue()))
            .toList());
  }

  public static int count(Inventory inv, Material material) {
    int n = 0;
    for (ItemStack item : inv.getContents())
      if (item != null && item.getType() == material) n += item.getAmount();
    return n;
  }

  public static int total(Inventory inv) {
    int n = 0;
    for (ItemStack item : inv.getContents()) if (item != null) n += item.getAmount();
    return n;
  }

  public static Map<String, Integer> summary(Inventory inv) {
    Map<String, Integer> m = new HashMap<>();
    for (ItemStack item : inv.getContents())
      if (item != null) m.merge(item.getType().name(), item.getAmount(), Integer::sum);
    return m;
  }

  public static void remove(Inventory inv, Material material, int count) {
    if (count(inv, material) < count) throw new IllegalArgumentException("Insufficient material");
    for (int i = 0; i < inv.getSize() && count > 0; i++) {
      ItemStack item = inv.getItem(i);
      if (item == null || item.getType() != material) continue;
      int n = Math.min(count, item.getAmount());
      count -= n;
      if (n == item.getAmount()) inv.setItem(i, null);
      else {
        ItemStack rest = item.clone();
        rest.setAmount(item.getAmount() - n);
        inv.setItem(i, rest);
      }
    }
  }

  public static int transfer(Inventory from, Inventory to, Set<Material> filter, int limit) {
    int moved = 0;
    for (int i = 0; i < from.getSize() && moved < limit; i++) {
      ItemStack stack = from.getItem(i);
      if (stack == null || filter != null && !filter.contains(stack.getType())) continue;
      int offer = Math.min(stack.getAmount(), limit - moved);
      ItemStack sent = stack.clone();
      sent.setAmount(offer);
      int remaining = to.addItem(sent).values().stream().mapToInt(ItemStack::getAmount).sum();
      int accepted = offer - remaining;
      if (accepted == 0) continue;
      int rest = stack.getAmount() - accepted;
      if (rest == 0) from.setItem(i, null);
      else {
        ItemStack updated = stack.clone();
        updated.setAmount(rest);
        from.setItem(i, updated);
      }
      moved += accepted;
    }
    return moved;
  }

  /** Recipes consume real raw ingredients; direct finished blocks are preferred. */
  public static void withdrawRecipe(Inventory chest, Inventory worker, Material output) {
    if (count(worker, output) > 0) return;
    if (count(chest, output) > 0) {
      transfer(chest, worker, Set.of(output), Math.min(8, count(chest, output)));
      return;
    }
    for (var ingredient : cost(output, worker).entrySet()) {
      int missing = Math.max(0, ingredient.getValue() - count(worker, ingredient.getKey()));
      if (missing > 0) transfer(chest, worker, Set.of(ingredient.getKey()), missing);
    }
  }

  public static Map<Material, Integer> cost(Material output, Inventory inv) {
    Map<Material, Integer> result = new LinkedHashMap<>();
    RecipeCatalog.cost(output.name(), summary(inv))
        .forEach((m, n) -> result.put(Material.valueOf(m), n));
    return result;
  }

  public static boolean has(Inventory inv, Map<Material, Integer> cost) {
    return cost.entrySet().stream().allMatch(e -> count(inv, e.getKey()) >= e.getValue());
  }

  public static void consumeRecipe(
      Inventory inv,
      Material output,
      Map<Material, Integer> cost,
      java.util.function.Consumer<ItemStack> overflow) {
    cost.forEach((m, n) -> remove(inv, m, n));
    if (cost.size() == 1 && cost.containsKey(output)) return;
    Map<String, Integer> named = new LinkedHashMap<>();
    cost.forEach((m, n) -> named.put(m.name(), n));
    RecipeCatalog.leftovers(output.name(), named)
        .forEach(
            (m, n) ->
                inv.addItem(new ItemStack(Material.valueOf(m), n)).values().forEach(overflow));
  }
}
