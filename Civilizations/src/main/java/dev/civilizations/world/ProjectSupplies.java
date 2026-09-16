package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Physical, persistent project bundles. These items never enter the community stock account. */
public final class ProjectSupplies {
  private final CivilizationsPlugin plugin;
  private final Villager actor;
  private final Settlement village;
  private final WorkerNavigation navigation;
  private final NamespacedKey bundle;

  public ProjectSupplies(
      CivilizationsPlugin plugin, Villager actor, Settlement village, WorkerNavigation navigation) {
    this.plugin = plugin;
    this.actor = actor;
    this.village = village;
    this.navigation = navigation;
    bundle = new NamespacedKey(plugin, "project_bundle");
  }

  public boolean makeRoom(Job job, String reason) {
    if (job == null || !Bukkit.isOwnedByCurrentRegion(actor.getLocation(), 1)) return false;
    var inv = actor.getInventory();
    Map<String, Integer> keep = new HashMap<>();
    if (job.kind == Job.Kind.PLACE) {
      keep.putAll(
          plugin.recipes().next(job.material, InventoryOps.summary(inv), true, true).cost());
      keep.put(job.material, 1);
    }
    int bestPick = -1, tier = 0;
    for (int i = 0; i < inv.getSize(); i++) {
      var item = inv.getItem(i);
      int candidate = item == null ? 0 : ToolRecipes.tier(Map.of(item.getType().name(), 1));
      if (candidate > tier) {
        tier = candidate;
        bestPick = i;
      }
    }
    int slot = -1, score = Integer.MAX_VALUE;
    for (int i = 0; i < inv.getSize(); i++) {
      var item = inv.getItem(i);
      if (item == null || item.getType().isAir() || i == bestPick) continue;
      int priority = keep.containsKey(item.getType().name()) ? 1 : 0;
      if (priority < score) {
        slot = i;
        score = priority;
      }
    }
    if (slot < 0) return false;
    ItemStack original = inv.getItem(slot).clone(), packed = original.clone();
    var meta = packed.getItemMeta();
    // A unique stack tag prevents vanilla merging bundles from different projects/owners.
    meta.getPersistentDataContainer()
        .set(bundle, PersistentDataType.STRING, UUID.randomUUID().toString());
    packed.setItemMeta(meta);
    Item item =
        actor
            .getWorld()
            .dropItem(
                actor.getLocation(),
                packed,
                dropped -> {
                  dropped.setCanMobPickup(false);
                  dropped.setCanPlayerPickup(false);
                  dropped.setUnlimitedLifetime(true);
                  dropped.setPickupDelay(Integer.MAX_VALUE);
                  dropped.setGravity(false);
                  dropped.setVelocity(new org.bukkit.util.Vector());
                });
    if (!item.isValid()) return false;
    inv.setItem(slot, null);
    String project = job.project;
    village.cache(
        new Settlement.SupplyCache(
            item.getUniqueId().toString(),
            project,
            actor.getUniqueId().toString(),
            pos(item.getLocation())));
    plugin.debug(
        village.id(),
        actor.getUniqueId().toString(),
        "project_supply_cached",
        Map.of(
            "project",
            project,
            "item_entity",
            item.getUniqueId().toString(),
            "material",
            original.getType().name(),
            "amount",
            original.getAmount(),
            "reason",
            reason,
            "community_deposit",
            false));
    return true;
  }

  public boolean obtain(Job job, Map<String, Integer> wanted, Pos at, long now) {
    return collect(job, wanted, false, at, now);
  }

  public boolean surplus(Pos at, long now) {
    return collect(null, Map.of(), true, at, now);
  }

  private boolean collect(Job job, Map<String, Integer> wanted, boolean surplus, Pos at, long now) {
    if (!surplus && wanted.isEmpty()) return false;
    for (var cache :
        village.caches().stream()
            .sorted(Comparator.comparingLong(c -> c.position().distance2(at)))
            .toList()) {
      if (surplus
          ? !village.allComplete(cache.project())
              || !village.mayShareSurplus(actor.getUniqueId().toString())
          : !cache.project().equals(job.project)) continue;
      Location location =
          new Location(
              actor.getWorld(), cache.position().x(), cache.position().y(), cache.position().z());
      if (!Bukkit.isOwnedByCurrentRegion(location, 1)) continue;
      Entity stored = actor.getWorld().getEntity(UUID.fromString(cache.entity()));
      if (!(stored instanceof Item item) || !item.isValid()) {
        if (actor.getWorld().isChunkLoaded(cache.position().x() >> 4, cache.position().z() >> 4))
          village.removeCache(cache.entity());
        continue;
      }
      if (!Bukkit.isOwnedByCurrentRegion(item)) continue;
      ItemStack contents = item.getItemStack().clone();
      var meta = contents.getItemMeta();
      if (!meta.getPersistentDataContainer().has(bundle)) {
        village.removeCache(cache.entity());
        continue;
      }
      meta.getPersistentDataContainer().remove(bundle);
      contents.setItemMeta(meta);
      int count =
          surplus
              ? contents.getAmount()
              : wanted.entrySet().stream()
                  .filter(e -> DeliveryBoard.matches(e.getKey(), contents.getType().name()))
                  .mapToInt(Map.Entry::getValue)
                  .max()
                  .orElse(0);
      if (count <= 0) continue;
      contents.setAmount(Math.min(contents.getAmount(), count));
      if (!InventoryOps.canFit(actor.getInventory(), List.of(contents))) {
        if (!surplus && makeRoom(job, "no room for reserved project supplies")) return true;
        continue;
      }
      Pos actual = pos(item.getLocation());
      village.cache(
          new Settlement.SupplyCache(cache.entity(), cache.project(), cache.owner(), actual));
      if (actor.getLocation().distanceSquared(item.getLocation()) > 4) {
        navigation.walkExact(actual, 0, now);
        return true;
      }
      int remaining = item.getItemStack().getAmount() - contents.getAmount();
      if (remaining == 0) {
        item.remove();
        village.removeCache(cache.entity());
      } else {
        var rest = item.getItemStack().clone();
        rest.setAmount(remaining);
        item.setItemStack(rest);
      }
      actor.getInventory().addItem(contents);
      plugin.debug(
          village.id(),
          actor.getUniqueId().toString(),
          "project_supply_retrieved",
          Map.of(
              "project",
              cache.project(),
              "material",
              contents.getType().name(),
              "amount",
              contents.getAmount(),
              "project_complete",
              village.allComplete(cache.project()),
              "item_entity",
              cache.entity()));
      return true;
    }
    return false;
  }

  private static Pos pos(Location p) {
    return new Pos(p.getBlockX(), p.getBlockY(), p.getBlockZ());
  }
}
