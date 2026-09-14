package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import java.util.*;
import java.util.function.BiConsumer;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;

/** Executes one tool prerequisite per work interval on the villager's owning region. */
public final class ToolActions {
  public record Preparation(boolean ready, String gather) {}

  private final CivilizationsPlugin plugin;
  private final Villager actor;
  private final Settlement village;
  private final WorkerNavigation navigation;
  private final BiConsumer<Long, String> failure;
  private long nextCraft;
  private final WorkerStations stations;

  public ToolActions(
      CivilizationsPlugin plugin,
      Villager actor,
      Settlement village,
      WorkerNavigation navigation,
      BiConsumer<Long, String> failure) {
    this.plugin = plugin;
    this.actor = actor;
    this.village = village;
    this.navigation = navigation;
    this.failure = failure;
    stations = new WorkerStations(actor, village, navigation);
  }

  private Location location(Pos p) {
    return new Location(actor.getWorld(), p.x() + .5, p.y(), p.z() + .5);
  }

  private boolean owns(Pos p) {
    return Bukkit.isOwnedByCurrentRegion(location(p), 1);
  }

  public Preparation prepare(int required, long now, Pos at) {
    Map<String, Integer> inventory = InventoryOps.summary(actor.getInventory());
    if (ToolRecipes.tier(inventory) >= required) return new Preparation(true, "");
    String output =
        required >= 2
                && (ToolRecipes.tier(inventory) > 0
                    || inventory.getOrDefault("COBBLESTONE", 0) >= 3)
            ? "STONE_PICKAXE"
            : "WOODEN_PICKAXE";
    return prepareItem(output, now, at);
  }

  public static String gatheringMaterial(String output, String ingredient) {
    return ingredient.endsWith("_LOG")
            && Set.of(
                    "CRAFTING_TABLE",
                    "WOODEN_PICKAXE",
                    "STONE_PICKAXE",
                    "TORCH",
                    "STICK",
                    "BREAD",
                    "WHITE_BED")
                .contains(output)
        ? "LOG"
        : ingredient;
  }

  public Preparation prepareItem(String outputItem, long now, Pos at) {
    Map<String, Integer> inventory = InventoryOps.summary(actor.getInventory());
    if (inventory.getOrDefault(outputItem, 0) > 0) return new Preparation(true, "");
    if (now < nextCraft) return new Preparation(false, "");
    Pos table = stations.choose(at, now);
    if (ChestSupplies.obtain(
        plugin, actor, village, navigation, outputItem, inventory, table != null, at, now))
      return new Preparation(false, "");
    CraftingBook.Step step = plugin.recipes().next(outputItem, inventory, table != null);
    String worker = actor.getUniqueId().toString();
    village
        .knowledge()
        .progress(
            worker,
            village.taskProject(worker),
            step.action()
                + " "
                + (step.action().equals("gather")
                    ? gatheringMaterial(outputItem, step.item())
                    : step.item()),
            "",
            "",
            now);
    if (step.action().equals("gather")) {
      String wanted = gatheringMaterial(outputItem, step.item());
      Pos chest =
          village.supplyChest(
              at,
              stock ->
                  NearbyWork.materials(wanted).stream()
                      .anyMatch(m -> stock.getOrDefault(m.name(), 0) > 0),
              now);
      if (chest != null
          && NearbyWork.materials(wanted).stream()
              .anyMatch(m -> village.stock().getOrDefault(m.name(), 0) > 0)
          && !village.knowledge().blocked("route:" + chest.key(), now)) {
        if (!owns(chest) || at.distance2(chest) > 12) {
          navigation.walk(chest, now);
          return new Preparation(false, "");
        }
        if (location(chest).getBlock().getState() instanceof Chest storage) {
          Map<String, Integer> beforeChest = InventoryOps.summary(storage.getInventory());
          Map<String, Integer> beforeActor = InventoryOps.summary(actor.getInventory());
          int moved =
              InventoryOps.transfer(
                  storage.getInventory(),
                  actor.getInventory(),
                  NearbyWork.materials(wanted),
                  Math.max(1, step.amount() - inventory.getOrDefault(step.item(), 0)));
          village.stock(chest, InventoryOps.summary(storage.getInventory()), now);
          if (moved > 0) {
            TransferReceipts.record(
                plugin,
                village,
                worker,
                "withdraw_prerequisite",
                "chest:" + chest.key(),
                worker,
                beforeChest,
                storage.getInventory(),
                beforeActor,
                actor.getInventory());
            village.remember(
                worker,
                "Withdrew "
                    + TransferReceipts.added(beforeActor, actor.getInventory())
                    + " for "
                    + outputItem,
                true);
            return new Preparation(false, "");
          }
        }
      }
      return new Preparation(false, wanted);
    }
    if (step.action().equals("craft") && step.table()) {
      if (table == null) return new Preparation(false, "");
      if (at.distance2(table) > 12 && village.knowledge().blocked("route:" + table.key(), now)) {
        failure.accept(now, "Crafting table currently unreachable; tool materials retained");
        return new Preparation(false, "");
      }
      if (!owns(table) || at.distance2(table) > 12) {
        navigation.walk(table, now);
        return new Preparation(false, "");
      }
    }
    if (step.action().equals("place_station")) {
      placeTable(at, now);
      return new Preparation(false, "");
    }
    if (!step.action().equals("craft")) return new Preparation(false, "");
    ItemStack output = new ItemStack(Material.valueOf(step.item()), step.amount());
    if (!InventoryOps.canExchange(actor.getInventory(), step.cost(), List.of(output))) {
      failure.accept(now, "Tool crafting needs inventory space; ingredients retained");
      return new Preparation(false, "");
    }
    step.cost()
        .forEach((m, n) -> InventoryOps.remove(actor.getInventory(), Material.valueOf(m), n));
    actor.getInventory().addItem(output);
    actor.swingMainHand();
    nextCraft = now + plugin.workMillis();
    String result =
        "Crafted "
            + step.amount()
            + " "
            + step.item()
            + " using "
            + step.cost()
            + " recipe="
            + step.recipe();
    village.remember(worker, result, true);
    village
        .knowledge()
        .progress(worker, village.taskProject(worker), "Next prerequisite", "", result, now);
    return new Preparation(false, "");
  }

  private void placeTable(Pos at, long now) {
    if (!owns(at)) return;
    synchronized (village) {
      if (stations.choose(at, now) != null) return;
      for (int[] d :
          new int[][] {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}}) {
        Pos p = at.add(d[0], 0, d[1]);
        if (!owns(p) || village.gatherProtected(p) || plugin.playerProtected(village, p)) continue;
        Block b = location(p).getBlock();
        if (!BlockRules.replaceable(b)
            || !BlockRules.dry(b)
            || !b.getRelative(BlockFace.DOWN).getType().isSolid()
            || !b.getRelative(BlockFace.UP).isPassable()
            || !plugin.mayChange(actor, b, "PLACE")) continue;
        if (InventoryOps.count(actor.getInventory(), Material.CRAFTING_TABLE) < 1) return;
        b.setType(Material.CRAFTING_TABLE, false);
        if (b.getType() != Material.CRAFTING_TABLE) continue;
        InventoryOps.remove(actor.getInventory(), Material.CRAFTING_TABLE, 1);
        village.craftingTable(p);
        stations.placed(p);
        village.remember(
            actor.getUniqueId().toString(), "Placed crafting table at " + p.key(), true);
        nextCraft = now + plugin.workMillis();
        return;
      }
    }
    failure.accept(now, "No dry unreserved crafting-table site within reach");
  }
}
