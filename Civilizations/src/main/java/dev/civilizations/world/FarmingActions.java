package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;

/** Executes one local wheat-plot action. Caller owns the entity and surrounding chunk region. */
public final class FarmingActions {
  public record Result(boolean complete, String problem) {}

  public static Result work(CivilizationsPlugin plugin, Villager actor, Block crop) {
    Block soil = crop.getRelative(BlockFace.DOWN);
    if (!Set.of(Material.DIRT, Material.GRASS_BLOCK, Material.FARMLAND).contains(soil.getType()))
      return new Result(false, "Farm soil changed");
    if (!hydrated(soil)) return new Result(false, "Farm soil is not hydrated by nearby water");
    if (!plugin.mayChange(actor, crop, "FARM") || !plugin.mayChange(actor, soil, "TILL"))
      return new Result(false, "Farm protected");
    if (crop.getType() == Material.WHEAT && crop.getBlockData() instanceof Ageable age) {
      if (age.getAge() < age.getMaximumAge())
        return new Result(true, "Wheat is growing; wait for maturity");
      List<ItemStack> drops =
          new ArrayList<>(crop.getDrops(new ItemStack(Material.WOODEN_HOE), actor));
      int seeds =
          drops.stream()
              .filter(i -> i.getType() == Material.WHEAT_SEEDS)
              .mapToInt(ItemStack::getAmount)
              .sum();
      if (seeds + InventoryOps.count(actor.getInventory(), Material.WHEAT_SEEDS) < 1)
        return new Result(false, "Keep one seed to replant before harvesting");
      if (!InventoryOps.canFit(actor.getInventory(), drops))
        return new Result(
            false, "Inventory full; retain task materials and complete another usable task");
      age.setAge(0);
      crop.setBlockData(age, false);
      for (ItemStack drop : drops) actor.getInventory().addItem(drop);
      InventoryOps.remove(actor.getInventory(), Material.WHEAT_SEEDS, 1);
      craftFood(plugin, actor);

      actor.swingMainHand();
      return new Result(true, "Harvested mature wheat and replanted with a real seed");
    }
    if (!Set.of(Material.AIR, Material.CAVE_AIR, Material.SHORT_GRASS, Material.TALL_GRASS)
        .contains(crop.getType())) return new Result(false, "Farm plot obstructed");
    if (!Set.of(Material.DIRT, Material.GRASS_BLOCK, Material.FARMLAND).contains(soil.getType()))
      return new Result(false, "Farm soil changed");
    if (InventoryOps.count(actor.getInventory(), Material.WHEAT_SEEDS) < 1)
      return new Result(false, "Missing WHEAT_SEEDS");
    BlockState oldSoil = soil.getState(), oldCrop = crop.getState();
    try {
      Farmland data = (Farmland) Material.FARMLAND.createBlockData();
      data.setMoisture(data.getMaximumMoisture());
      soil.setBlockData(data, false);
      crop.setBlockData(Material.WHEAT.createBlockData(), false);
    } catch (RuntimeException e) {
      oldSoil.update(true, false);
      oldCrop.update(true, false);
      return new Result(false, "Planting failed; seed retained");
    }
    InventoryOps.remove(actor.getInventory(), Material.WHEAT_SEEDS, 1);
    actor.swingMainHand();
    return new Result(true, "Planted wheat using one seed");
  }

  private static boolean hydrated(Block soil) {
    for (int x = -4; x <= 4; x++)
      for (int z = -4; z <= 4; z++)
        for (int y = 0; y <= 1; y++)
          if (soil.getRelative(x, y, z).getType() == Material.WATER) return true;
    return false;
  }

  private static void craftFood(CivilizationsPlugin plugin, Villager actor) {
    boolean table = false;
    Block at = actor.getLocation().getBlock();
    for (int x = -3; x <= 3; x++)
      for (int z = -3; z <= 3; z++)
        for (int y = -2; y <= 2; y++) {
          Block b = at.getRelative(x, y, z);
          if (x * x + y * y + z * z <= 12
              && Bukkit.isOwnedByCurrentRegion(b.getLocation())
              && b.getType() == Material.CRAFTING_TABLE) table = true;
        }
    if (!table) return;
    while (InventoryOps.count(actor.getInventory(), Material.BREAD) < 3) {
      Map<String, Integer> inv = InventoryOps.summary(actor.getInventory());
      inv.remove("BREAD");
      var step = plugin.recipes().next("BREAD", inv, true);
      ItemStack output = new ItemStack(Material.BREAD, step.amount());
      if (!step.action().equals("craft")
          || !InventoryOps.canExchange(actor.getInventory(), step.cost(), List.of(output))) return;
      step.cost()
          .forEach((m, n) -> InventoryOps.remove(actor.getInventory(), Material.valueOf(m), n));
      actor.getInventory().addItem(output);
    }
  }
}
