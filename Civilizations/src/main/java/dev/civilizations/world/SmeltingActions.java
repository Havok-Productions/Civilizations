package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import java.util.*;
import java.util.function.BiConsumer;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.*;

/** Supplies a real furnace and collects its actual result. Vanilla owns fuel and cooking time. */
final class SmeltingActions {
  private final CivilizationsPlugin plugin;
  private final Villager actor;
  private final Settlement village;
  private final WorkerNavigation navigation;
  private final WorkerStations stations;
  private final WorkPose pose;
  private final BiConsumer<Long, String> failure;
  private Pos batch;
  private String output = "";
  private long lastProgress, nextReport;
  private int cookTime = -1;
  private boolean missingFuel;

  SmeltingActions(
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
    stations = new WorkerStations(actor, village, navigation, Material.FURNACE);
    pose = new WorkPose(plugin, actor);
  }

  boolean available(Pos at, long now) {
    return stations.choose(at, now) != null;
  }

  boolean processing(String item) {
    return batch != null && output.equals(item);
  }

  boolean processing() {
    return batch != null;
  }

  /** Keep the batch, but expose its missing fuel to normal chest/delivery/gathering recovery. */
  boolean needsFuel(Pos at, long now) {
    if (batch == null) return false;
    if (!Bukkit.isOwnedByCurrentRegion(location(batch), 1))
      return missingFuel
          && SmeltingFuel.choose(InventoryOps.summary(actor.getInventory()), Map.of()) == null;
    if (!(location(batch).getBlock().getState() instanceof Furnace furnace)) return false;
    var inventory = furnace.getInventory();
    var result = inventory.getResult();
    missingFuel =
        SmeltingFuel.missing(
            inventory.getSmelting() != null,
            result != null && result.getType().name().equals(output),
            furnace.getBurnTime() > 0,
            inventory.getFuel() != null && !inventory.getFuel().getType().isAir(),
            InventoryOps.summary(actor.getInventory()));
    if (missingFuel) lastProgress = now;
    return missingFuel;
  }

  boolean resumePending(Pos at, long now) {
    return batch != null && resume(output, at, now);
  }

  boolean resume(String item, Pos at, long now) {
    if (!processing(item)) {
      var recipes = plugin.recipes().furnaceRecipes(item);
      if (recipes.isEmpty()) return false;
      Pos candidate = stations.choose(at, now);
      if (candidate == null
          || at.distance2(candidate) > 12
          || !Bukkit.isOwnedByCurrentRegion(location(candidate), 1)) return false;
      if (!(location(candidate).getBlock().getState() instanceof Furnace f)) return false;
      var inputs = recipes.stream().flatMap(r -> r.slots().getFirst().stream()).toList();
      ItemStack input = f.getInventory().getSmelting(), result = f.getInventory().getResult();
      if (!(result != null && result.getType().name().equals(item)
          || input != null && inputs.contains(input.getType().name()))) return false;
      if (!village.reserveGather(candidate, actor.getUniqueId().toString(), now)) return true;
      batch = candidate;
      output = item;
      lastProgress = now;
    }
    Pos p = batch;
    if (!village.reserveGather(p, actor.getUniqueId().toString(), now)) return true;
    if (!near(p, at, now)) return true;
    if (!(location(p).getBlock().getState() instanceof Furnace furnace)) {
      batch = null;
      failure.accept(
          now, "Smelting station disappeared; inspect deposited ingredients at " + p.key());
      return true;
    }
    FurnaceInventory inv = furnace.getInventory();
    ItemStack result = inv.getResult();
    if (result != null && result.getType().name().equals(output)) {
      if (!InventoryOps.canFit(actor.getInventory(), List.of(result))) {
        failure.accept(now, "Inventory full; smelted output retained in furnace");
        return true;
      }
      if (!pose.ready(location(p).getBlock(), now)) return true;
      var before = InventoryOps.summary(actor.getInventory());
      ItemStack collected = result.clone();
      inv.setResult(null);
      actor.getInventory().addItem(collected);
      actor.swingMainHand();
      plugin.debug(
          village.id(),
          actor.getUniqueId().toString(),
          "smelting_result",
          Map.of(
              "station",
              p,
              "output",
              output,
              "amount",
              collected.getAmount(),
              "inventory_before",
              before,
              "inventory_after",
              InventoryOps.summary(actor.getInventory()),
              "verified",
              true));
      village.remember(
          actor.getUniqueId().toString(),
          "Collected " + collected.getAmount() + " " + output + " from furnace at " + p.key(),
          true);
      batch = null;
      pose.reset();
      return true;
    }
    if (furnace.getBurnTime() <= 0
        && inv.getSmelting() != null
        && (inv.getFuel() == null || inv.getFuel().getType().isAir())) {
      var carried = InventoryOps.summary(actor.getInventory());
      String fuel = SmeltingFuel.choose(carried, Map.of());
      if (fuel != null
          && pose.ready(location(p).getBlock(), now)
          && plugin.mayChange(actor, location(p).getBlock(), "SMELT")
          && InventoryOps.count(actor.getInventory(), Material.valueOf(fuel)) > 0) {
        InventoryOps.remove(actor.getInventory(), Material.valueOf(fuel), 1);
        inv.setFuel(new ItemStack(Material.valueOf(fuel), 1));
        actor.swingMainHand();
        lastProgress = now;
        plugin.debug(
            village.id(),
            actor.getUniqueId().toString(),
            "smelting_refuel",
            Map.of(
                "station",
                p,
                "fuel",
                fuel,
                "amount",
                1,
                "inventory_before",
                carried,
                "inventory_after",
                InventoryOps.summary(actor.getInventory())));
      }
    }
    if (cookTime != furnace.getCookTime()) {
      cookTime = furnace.getCookTime();
      lastProgress = now;
    }
    if (now >= nextReport) {
      nextReport = now + 5000;
      plugin.debug(
          village.id(),
          actor.getUniqueId().toString(),
          "smelting_wait",
          Map.of(
              "station",
              p,
              "output",
              output,
              "cook_ticks",
              cookTime,
              "total_ticks",
              furnace.getCookTimeTotal(),
              "burn_ticks",
              furnace.getBurnTime()));
    }
    if (now - lastProgress > 30000) {
      batch = null;
      failure.accept(now, "Furnace made no cooking progress; ingredients remain at " + p.key());
    }
    return true;
  }

  void start(CraftingBook.Step step, Pos at, long now) {
    Pos p = stations.choose(at, now);
    if (p == null
        || !village.reserveGather(p, actor.getUniqueId().toString(), now)
        || !near(p, at, now)) return;
    if (!(location(p).getBlock().getState() instanceof Furnace furnace)) return;
    if (!pose.ready(location(p).getBlock(), now)) return;
    if (!plugin.mayChange(actor, location(p).getBlock(), "SMELT")) {
      failure.accept(now, "Furnace access blocked by protection");
      return;
    }
    FurnaceInventory inv = furnace.getInventory();
    // A previous worker or restart may leave this same recipe cooking. Adopt the useful work.
    var recipe =
        plugin.recipes().furnaceRecipes(step.item()).stream()
            .filter(r -> r.key().equals(step.recipe()))
            .findFirst()
            .orElseThrow();
    ItemStack input = inv.getSmelting(), result = inv.getResult();
    if (result != null && result.getType().name().equals(step.item())
        || input != null && recipe.slots().getFirst().contains(input.getType().name())) {
      batch = p;
      output = step.item();
      lastProgress = now;
      return;
    }
    if (input != null && !input.getType().isAir() || result != null && !result.getType().isAir()) {
      village
          .knowledge()
          .block(
              "station_busy:FURNACE:" + p.key(),
              "Furnace is processing another recipe",
              now,
              15000);
      return;
    }
    var carried = InventoryOps.summary(actor.getInventory());
    String raw =
        recipe.slots().getFirst().stream()
            .filter(m -> carried.getOrDefault(m, 0) > 0)
            .findFirst()
            .orElse(null);
    if (raw == null) return;
    String fuel = SmeltingFuel.choose(carried, Map.of(raw, 1));
    boolean needsFuel =
        furnace.getBurnTime() <= 0 && (inv.getFuel() == null || inv.getFuel().getType().isAir());
    if (needsFuel && fuel == null) return;
    Map<String, Integer> cost = new HashMap<>();
    cost.put(raw, 1);
    if (needsFuel) cost.merge(fuel, 1, Integer::sum);
    if (cost.entrySet().stream().anyMatch(e -> carried.getOrDefault(e.getKey(), 0) < e.getValue()))
      return;
    cost.forEach((m, n) -> InventoryOps.remove(actor.getInventory(), Material.valueOf(m), n));
    inv.setSmelting(new ItemStack(Material.valueOf(raw), 1));
    if (needsFuel) inv.setFuel(new ItemStack(Material.valueOf(fuel), 1));
    batch = p;
    output = step.item();
    lastProgress = now;
    cookTime = -1;
    actor.swingMainHand();
    plugin.debug(
        village.id(),
        actor.getUniqueId().toString(),
        "smelting_started",
        Map.of(
            "station",
            p,
            "recipe",
            step.recipe(),
            "cost",
            cost,
            "inventory_before",
            carried,
            "inventory_after",
            InventoryOps.summary(actor.getInventory()),
            "basis",
            "inputs transferred; output not yet produced"));
  }

  void place(Pos at, long now) {
    if (available(at, now)) return;
    for (int[] d : new int[][] {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {1, 1}, {-1, -1}}) {
      Pos p = at.add(d[0], 0, d[1]);
      if (!Bukkit.isOwnedByCurrentRegion(location(p), 1)
          || village.gatherProtected(p)
          || plugin.playerProtected(village, p)) continue;
      Block b = location(p).getBlock();
      if (!b.getType().isAir()
          || !BlockRules.dry(b)
          || !b.getRelative(BlockFace.DOWN).getType().isSolid()
          || !b.getRelative(BlockFace.UP).isPassable()) continue;
      if (!pose.ready(b, now)) return;
      if (!plugin.mayChange(actor, b, "PLACE")
          || !b.getType().isAir()
          || InventoryOps.count(actor.getInventory(), Material.FURNACE) < 1) return;
      b.setType(Material.FURNACE, false);
      if (b.getType() != Material.FURNACE) return;
      InventoryOps.remove(actor.getInventory(), Material.FURNACE, 1);
      stations.placed(p);
      village.recordRepair(new RepairBlock(p, "FURNACE", b.getBlockData().getAsString()));
      actor.swingMainHand();
      pose.reset();
      village.remember(actor.getUniqueId().toString(), "Placed furnace at " + p.key(), true);
      return;
    }
    failure.accept(
        now, "No supported furnace site within reach; retain station and try another work area");
  }

  private boolean near(Pos p, Pos at, long now) {
    if (at.distance2(p) > 12 || !Bukkit.isOwnedByCurrentRegion(location(p), 1)) {
      navigation.walk(p, now);
      return false;
    }
    return true;
  }

  private Location location(Pos p) {
    return new Location(actor.getWorld(), p.x() + .5, p.y(), p.z() + .5);
  }
}
