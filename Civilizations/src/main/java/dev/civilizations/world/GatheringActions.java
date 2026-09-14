package dev.civilizations.world;

import static dev.civilizations.world.BlockRules.*;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import java.util.*;
import java.util.Comparator;
import java.util.function.BiConsumer;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;

/** Nearby gathering only, with real drops, inventory capacity, and entity-owned navigation. */
public final class GatheringActions {
  private final CivilizationsPlugin plugin;
  private final Villager entity;
  private final Settlement village;
  private final String id;
  private final WorkerNavigation navigation;
  private final RecoveryPolicy recovery;
  private final BiConsumer<Long, String> failure;
  private String resource = "";
  private Pos target;
  private long nextWork, nextLocalScan;
  private List<Pos> nearbySites = List.of();
  private boolean needsSupply;

  public GatheringActions(
      CivilizationsPlugin plugin,
      Villager entity,
      Settlement village,
      WorkerNavigation navigation,
      RecoveryPolicy recovery,
      BiConsumer<Long, String> failure) {
    this.plugin = plugin;
    this.entity = entity;
    this.village = village;
    this.navigation = navigation;
    this.recovery = recovery;
    this.failure = failure;
    id = entity.getUniqueId().toString();
  }

  public boolean gather(String material, long now, Pos at) {
    if (!material.equals(resource)) {
      target = null;
      resource = material;
      nextLocalScan = 0;
    }
    needsSupply = false;
    step(now, at);
    return needsSupply;
  }

  public void reset() {
    target = null;
  }

  private Location location(Pos p) {
    return new Location(entity.getWorld(), p.x() + .5, p.y(), p.z() + .5);
  }

  private boolean owns(Pos p, int radius) {
    return Bukkit.isOwnedByCurrentRegion(location(p), radius);
  }

  private void walk(Pos p, long now) {
    navigation.walk(p, now);
  }

  private void fail(long now, String reason) {
    failure.accept(now, reason);
  }

  private void step(long now, Pos at) {
    if (resource.equals("WHITE_WOOL")) {
      gatherWool(now, at);
      return;
    }
    if (!Set.of("COBBLESTONE", "COAL", "LOG", "WHEAT_SEEDS").contains(resource)
        && !resource.endsWith("_LOG")) {
      fail(now, "No supported local gathering method for " + resource);
      return;
    }
    if (target == null) {
      if (now >= nextLocalScan) {
        nearbySites = NearbyResources.find(entity, at, resource);
        nextLocalScan = now + 5000;
      }
      List<Pos> candidates =
          java.util.stream.Stream.concat(
                  nearbySites.stream(), plugin.resources(village.id(), resource).stream())
              .distinct()
              .toList();
      target =
          candidates.stream()
              .filter(p -> !village.gatherProtected(p) && !plugin.playerProtected(village, p))
              .filter(p -> !village.knowledge().blocked("route:" + p.key(), now))
              .sorted(Comparator.comparingLong(p -> p.distance2(at)))
              .filter(p -> village.reserveGather(p, id, now))
              .findFirst()
              .orElse(null);
      if (target == null) {
        plugin.debug(
            village.id(),
            id,
            "resource_search",
            Map.of(
                "resource",
                resource,
                "position",
                at,
                "known_sites",
                candidates.size(),
                "protected_sites",
                candidates.stream()
                    .filter(p -> village.gatherProtected(p) || plugin.playerProtected(village, p))
                    .count(),
                "blocked_routes",
                candidates.stream()
                    .filter(p -> village.knowledge().blocked("route:" + p.key(), now))
                    .count(),
                "result",
                "No unreserved, unprotected candidate with an unblocked route"));
        needsSupply = true;
        return;
      }
    }
    if (at.distance2(target) > 12) {
      walk(target, now);
      return;
    }
    if (!owns(target, 1) || now < nextWork) return;
    Block block = location(target).getBlock();
    if ((resource.equals("LOG") || resource.endsWith("_LOG")) && tree(block)) {
      for (int h = 0;
          h < 8
              && block.getRelative(BlockFace.UP).getType() == block.getType()
              && at.distance2(new Pos(block.getX(), block.getY() + 1, block.getZ())) <= 21;
          h++) block = block.getRelative(BlockFace.UP);
      target = new Pos(block.getX(), block.getY(), block.getZ());
      // Only fell reachable trunk blocks; never remotely harvest a tall tree.
      if (at.distance2(target) > 21) {
        plugin.exhausted(village.id(), new Pos(block.getX(), at.y(), block.getZ()));
        fail(now, "Tree trunk is beyond ordinary villager reach");
        return;
      }
    }
    if (village.gatherProtected(target) || plugin.playerProtected(village, target)) {
      exhausted(target);
      target = null;
      return;
    }
    if (resource.equals("WHEAT_SEEDS")) {
      gatherSeeds(block, now);
      return;
    }
    boolean match =
        (resource.equals("LOG") || resource.endsWith("_LOG"))
            ? block.getType().name().endsWith("_LOG")
                && (resource.equals("LOG") || block.getType().name().equals(resource))
            : resource.equals("COAL")
                ? Set.of(Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE).contains(block.getType())
                : block.getType() == Material.STONE;
    if (!match || !dry(block) || !safeMining(block) || !plugin.mayChange(entity, block, "GATHER")) {
      exhausted(target);
      target = null;
      return;
    }
    if ((resource.equals("LOG") || resource.endsWith("_LOG")) && !tree(block)) {
      exhausted(target);
      target = null;
      return;
    }
    Material actualDrop =
        resource.equals("LOG") || resource.endsWith("_LOG")
            ? block.getType()
            : Material.valueOf(resource);
    if (!room(actualDrop, 1, now)) return;
    if (!MiningTools.has(entity.getInventory(), block.getType().name())) {
      fail(now, "Gathering " + resource + " requires a crafted pickaxe");
      return;
    }
    String mined = block.getType().name();
    block.setType(Material.AIR, false);
    if (!block.getType().isAir()) {
      fail(now, "Resource removal failed");
      return;
    }
    MiningTools.used(entity.getInventory(), mined);
    give(actualDrop, 1);
    village.knowledge().clear("resource:" + resource);
    village
        .knowledge()
        .progress(
            id,
            village.taskProject(id),
            "Next prerequisite",
            "",
            "Gathered " + actualDrop + " at " + target.key(),
            now);
    recovery.progress(now);
    entity.swingMainHand();
    village.remember(id, "Gathered " + actualDrop + " at " + target.key(), true);
    exhausted(target);
    target = null;
    nextWork = now + plugin.workMillis();
  }

  private void exhausted(Pos p) {
    plugin.exhausted(village.id(), p);
    nearbySites = nearbySites.stream().filter(site -> !site.equals(p)).toList();
  }

  private void gatherWool(long now, Pos at) {
    if (!owns(at, 1)) return;
    for (Entity e : entity.getNearbyEntities(12, 5, 12))
      if (e instanceof Sheep sheep
          && Bukkit.isOwnedByCurrentRegion(sheep)
          && !sheep.isSheared()
          && sheep.isAdult()
          && sheep.getColor() == DyeColor.WHITE) {
        Pos p =
            new Pos(
                e.getLocation().getBlockX(),
                e.getLocation().getBlockY(),
                e.getLocation().getBlockZ());
        if (at.distance2(p) > 9) {
          walk(p, now);
          return;
        }
        if (now < nextWork) return;
        // Workers have basic role tools; wool follows the sheep's renewable sheared state.
        if (!room(Material.WHITE_WOOL, 1, now)) return;
        sheep.setSheared(true);
        recovery.progress(now);
        give(Material.WHITE_WOOL, 1);
        nextWork = now + 2000;
        village.remember(id, "Sheared a white sheep", true);
        return;
      }
    fail(now, "No reachable adult white sheep with wool nearby");
  }

  private void gatherSeeds(Block block, long now) {
    if (!Set.of(Material.SHORT_GRASS, Material.TALL_GRASS).contains(block.getType())
        || !plugin.mayChange(entity, block, "GATHER")) {
      exhausted(target);
      target = null;
      return;
    }
    Collection<ItemStack> drops = block.getDrops(new ItemStack(Material.AIR), entity);
    if (!InventoryOps.canFit(entity.getInventory(), drops)) {
      fail(now, "Inventory full; seeds not harvested");
      return;
    }
    block.setType(Material.AIR, false);
    drops.forEach(i -> entity.getInventory().addItem(i));
    // Breaking grass without finding a seed is not task progress.
    if (drops.stream().anyMatch(i -> i.getType() == Material.WHEAT_SEEDS)) recovery.progress(now);
    entity.swingMainHand();
    exhausted(target);
    target = null;
    nextWork = now + plugin.workMillis();
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
