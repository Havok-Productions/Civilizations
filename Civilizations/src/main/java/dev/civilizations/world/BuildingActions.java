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

/** Bounded local construction/mining; callers own the entity and surrounding region. */
public final class BuildingActions {
  private final CivilizationsPlugin plugin;
  private final Villager entity;
  private final BiConsumer<Long, String> failure;
  private final LongConsumer completion;
  private final BiConsumer<Long, PlacementSpace.Obstruction> waiting;
  private Job job;
  private long nextAction;

  public BuildingActions(
      CivilizationsPlugin plugin,
      Villager entity,
      BiConsumer<Long, String> failure,
      LongConsumer completion,
      BiConsumer<Long, PlacementSpace.Obstruction> waiting) {
    this.plugin = plugin;
    this.entity = entity;
    this.failure = failure;
    this.completion = completion;
    this.waiting = waiting;
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
    boolean reuse = WorkState.reusable(job, block);
    if (!replaceable(block)
        && !reuse
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
    if ((!replaceable(block) && !reuse) || !dry(block)) {
      fail(now, "Build site changed or became wet");
      return;
    }
    Material material = Material.valueOf(job.material);
    Map<Material, Integer> cost = reuse ? Map.of() : Map.of(material, 1);
    if (!InventoryOps.has(entity.getInventory(), cost)) return;
    Block head = null;
    BlockData data = PlacementSpace.data(job);
    Block oldHead = null;
    if (data instanceof Bed bed) {
      head = block.getRelative(bed.getFacing());
      if ((!replaceable(head)
              && !(head.getType() == material
                  && head.getBlockData() instanceof Bed h
                  && h.getPart() == Bed.Part.HEAD
                  && h.getFacing() == bed.getFacing()))
          || !head.getRelative(BlockFace.DOWN).getType().isSolid()
          || !plugin.mayChange(entity, head, "PLACE")) {
        fail(now, "Bed needs two clear supported spaces");
        return;
      }
      if (reuse
          && block.getBlockData() instanceof Bed previous
          && previous.getFacing() != bed.getFacing()) {
        Block old = block.getRelative(previous.getFacing());
        if (old.getType() == material
            && old.getBlockData() instanceof Bed h
            && h.getPart() == Bed.Part.HEAD
            && h.getFacing() == previous.getFacing()) {
          if (!plugin.mayChange(entity, old, "REORIENT_BED")) {
            fail(now, "Old bed head is protected");
            return;
          }
          oldHead = old;
        }
      }
    }
    if ((material == Material.TORCH || data instanceof Bed)
        && !block.getRelative(BlockFace.DOWN).getType().isSolid()) {
      fail(now, "Support block missing");
      return;
    }
    if (!reuse && !InventoryOps.canCraft(entity.getInventory(), material, cost)) {
      fail(now, "Inventory full: cannot retain crafting leftovers");
      return;
    }
    // Recheck after permission listeners and immediately before mutating either half of a bed.
    var obstruction = PlacementSpace.obstruction(block, data);
    if (obstruction != null) {
      waiting.accept(now, obstruction);
      return;
    }
    BlockState previous = block.getState(), headBefore = head == null ? null : head.getState();
    BlockState oldHeadBefore = oldHead == null ? null : oldHead.getState();
    try {
      if (oldHead != null) oldHead.setType(Material.AIR, false);
      block.setBlockData(data, false);
      if (head != null) {
        Bed upper = (Bed) data.clone();
        upper.setPart(Bed.Part.HEAD);
        head.setBlockData(upper, false);
      }
      if (!WorkState.satisfied(job, block))
        throw new IllegalStateException("Requested block state did not persist");
    } catch (Exception e) {
      previous.update(true, false);
      if (headBefore != null) headBefore.update(true, false);
      if (oldHeadBefore != null) oldHeadBefore.update(true, false);
      fail(now, "Placement failed; materials retained");
      return;
    }
    if (!reuse)
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
    Material type = block.getType();
    if (!MiningTools.has(entity.getInventory(), type.name())) {
      fail(now, "Mine face requires an adequate crafted pickaxe");
      return;
    }
    var drops =
        List.copyOf(block.getDrops(MiningTools.tool(entity.getInventory(), type.name()), entity));
    if (!InventoryOps.canFit(entity.getInventory(), drops)) {
      fail(now, "Inventory full; task materials retained");
      return;
    }
    block.setType(Material.AIR, false);
    if (!block.getType().isAir()) {
      fail(now, "Block removal failed");
      return;
    }
    MiningTools.used(entity.getInventory(), type.name());
    drops.forEach(item -> entity.getInventory().addItem(item));
    entity.swingMainHand();
    block.getWorld().playSound(block.getLocation(), Sound.BLOCK_STONE_BREAK, 0.4f, 1);
    complete(now);
  }
}
