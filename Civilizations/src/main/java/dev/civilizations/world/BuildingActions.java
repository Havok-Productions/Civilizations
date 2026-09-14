package dev.civilizations.world;

import static dev.civilizations.world.BlockRules.*;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.Job;
import java.util.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.*;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;

/** Bounded local construction/mining; callers own the entity and surrounding region. */
public final class BuildingActions {
  private final CivilizationsPlugin plugin;
  private final Villager entity;
  private final BiConsumer<Long, String> failure;
  private final LongConsumer completion;
  private Job job;
  private long nextAction;

  public BuildingActions(
      CivilizationsPlugin plugin,
      Villager entity,
      BiConsumer<Long, String> failure,
      LongConsumer completion) {
    this.plugin = plugin;
    this.entity = entity;
    this.failure = failure;
    this.completion = completion;
  }

  public void execute(Job job, Block block, long now) {
    if (now < nextAction) return;
    this.job = job;
    if (job.kind == Job.Kind.MINE) mine(block, now);
    else place(block, now);
  }

  private void fail(long now, String reason) {
    failure.accept(now, reason);
  }

  private void complete(long now) {
    completion.accept(now);
  }

  private void place(Block block, long now) {
    if (!replaceable(block)
        && plugin.experiments() != null
        && BlockObservation.learnedClear(
            plugin.experiments().rules(), entity.getUniqueId().toString(), block)) {
      String before = block.getBlockData().getAsString();
      if (!dry(block) || !plugin.mayChange(entity, block, "CLEAR_SITE")) {
        fail(now, "Learned site clearance blocked or wet");
        return;
      }
      if (!before.equals(block.getBlockData().getAsString())) {
        fail(now, "Classified site changed during protection event");
        return;
      }
      var drops = List.copyOf(block.getDrops());
      if (!InventoryOps.canFit(entity.getInventory(), drops)) {
        fail(now, "Inventory full for classified site drops");
        return;
      }
      block.setType(Material.AIR, false);
      if (!block.getType().isAir()) {
        fail(now, "Classified site removal did not persist");
        return;
      }
      drops.forEach(item -> entity.getInventory().addItem(item));
      nextAction = now + WorkerTuning.value(plugin, entity, "construction.interval_ms");
      entity.swingMainHand();
      return;
    }
    if ((!replaceable(block)
            && !(job.material.equals("WHITE_BED") && block.getType() == Material.WHITE_BED))
        || !dry(block)) {
      fail(now, "Build site changed or became wet");
      return;
    }
    Material material = Material.valueOf(job.material);
    Map<Material, Integer> cost = Map.of(material, 1);
    if (!InventoryOps.has(entity.getInventory(), cost)) return;
    Block head = null;
    BlockData data =
        job.blockData == null ? material.createBlockData() : Bukkit.createBlockData(job.blockData);
    if (data instanceof Bed bed) {
      head = block.getRelative(bed.getFacing());
      if (!replaceable(head)
          || !head.getRelative(BlockFace.DOWN).getType().isSolid()
          || !plugin.mayChange(entity, head, "PLACE")) {
        fail(now, "Bed needs two clear supported spaces");
        return;
      }
    }
    if ((material == Material.TORCH || data instanceof Bed)
        && !block.getRelative(BlockFace.DOWN).getType().isSolid()) {
      fail(now, "Support block missing");
      return;
    }
    if (!InventoryOps.canCraft(entity.getInventory(), material, cost)) {
      fail(now, "Inventory full: cannot retain crafting leftovers");
      return;
    }
    BlockState previous = block.getState(), headBefore = head == null ? null : head.getState();
    try {
      block.setBlockData(data, false);
      if (head != null) {
        Bed upper = (Bed) data.clone();
        upper.setPart(Bed.Part.HEAD);
        head.setBlockData(upper, false);
      }
      if (block.getType() != material) throw new IllegalStateException("Placement did not persist");
    } catch (Exception e) {
      previous.update(true, false);
      if (headBefore != null) headBefore.update(true, false);
      fail(now, "Placement failed; materials retained");
      return;
    }
    InventoryOps.consumeRecipe(
        entity.getInventory(),
        material,
        cost,
        item -> entity.getWorld().dropItemNaturally(entity.getLocation(), item));
    entity.swingMainHand();
    block.getWorld().playSound(block.getLocation(), Sound.BLOCK_STONE_PLACE, 0.4f, 1);
    complete(now);
  }

  private void mine(Block block, long now) {
    boolean soil =
        Set.of("DIRT", "GRASS_BLOCK").contains(job.expected)
            && Set.of(Material.DIRT, Material.GRASS_BLOCK).contains(block.getType());
    if ((!block.getType().name().equals(job.expected) && !soil)
        || !natural(block)
        || !dry(block)
        || !safeMining(block)) {
      fail(
          now,
          "Mine face changed or is unsafe (expected "
              + job.expected
              + ", found "
              + block.getType()
              + ")");
      return;
    }
    Material type = block.getType(), drop = drop(type);
    if (!MiningTools.has(entity.getInventory(), type.name())) {
      fail(now, "Mine face requires an adequate crafted pickaxe");
      return;
    }
    int amount = 1;
    if (!room(drop, amount, now)) return;
    block.setType(Material.AIR, false);
    if (!block.getType().isAir()) {
      fail(now, "Block removal failed");
      return;
    }
    MiningTools.used(entity.getInventory(), type.name());
    give(drop, amount);
    entity.swingMainHand();
    block.getWorld().playSound(block.getLocation(), Sound.BLOCK_STONE_BREAK, 0.4f, 1);
    complete(now);
  }

  private boolean room(Material material, int amount, long now) {
    if (InventoryOps.canFit(entity.getInventory(), List.of(new ItemStack(material, amount))))
      return true;
    fail(now, "Inventory full; task materials retained");
    return false;
  }

  private void give(Material material, int amount) {
    entity.getInventory().addItem(new ItemStack(material, amount));
  }
}
