package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;

/** A courier walks to a worker and transfers real items on their shared owning region. */
public final class DeliveryActions {
  private final CivilizationsPlugin plugin;
  private final Villager actor;
  private final Settlement village;
  private final WorkerNavigation navigation;
  private final String id;
  private DeliveryBoard.Delivery current;
  private long next;

  public DeliveryActions(
      CivilizationsPlugin plugin, Villager actor, Settlement village, WorkerNavigation navigation) {
    this.plugin = plugin;
    this.actor = actor;
    this.village = village;
    this.navigation = navigation;
    id = actor.getUniqueId().toString();
  }

  public void cancel() {
    village.deliveries().remove(id);
    current = null;
  }

  public boolean active() {
    return current != null;
  }

  public String status() {
    return current == null
        ? ""
        : "delivering " + current.material() + " to " + current.recipient().substring(0, 8);
  }

  public void publish(Pos at, Job job, Job parent, Map<String, Integer> requests, long now) {
    Map<String, Integer> inv = InventoryOps.summary(actor.getInventory());
    Map<String, Integer> offer = new HashMap<>(RecipeCatalog.surplus(inv, true));
    for (Job work : new Job[] {job, parent}) {
      if (work == null) continue;
      String output =
          switch (work.kind) {
            case PLACE -> work.material;
            case FARM -> "WHEAT_SEEDS";
            case MINE -> ToolRecipes.tier(inv) == 0 ? "WOODEN_PICKAXE" : "STONE_PICKAXE";
            default -> "";
          };
      plugin
          .recipes()
          .reserved(output, inv)
          .forEach((m, n) -> offer.computeIfPresent(m, (k, v) -> Math.max(0, v - n)));
    }
    village
        .deliveries()
        .publish(id, new DeliveryBoard.Worker(at, job == null ? "" : job.id, requests, offer, now));
  }

  public boolean tick(long now) {
    if (now < next && current == null) return false;
    DeliveryBoard board = village.deliveries();
    DeliveryBoard.Delivery selected = board.claim(id, now);
    if (selected == null) {
      if (current != null) {
        navigation.stop();
        plugin.debug(
            village.id(),
            id,
            "delivery_cancelled",
            Map.of(
                "delivery",
                current,
                "reason",
                "Request changed, participant unavailable, or offered materials now needed locally",
                "verified_action",
                false));
      }
      current = null;
      return false;
    }
    if (!selected.equals(current)) {
      navigation.stop();
      current = selected;
      plugin.debug(
          village.id(),
          id,
          "delivery_requested",
          Map.of("delivery", current, "verified_action", false));
    }
    DeliveryBoard.Worker target = board.worker(current.recipient());
    if (Bukkit.isOwnedByCurrentRegion(actor.getLocation(), 1))
      for (Entity other : actor.getNearbyEntities(3, 2, 3)) {
        if (!(other instanceof Villager peer)
            || !Bukkit.isOwnedByCurrentRegion(peer)
            || !peer.isValid()
            || peer.isSleeping()
            || !peer.getUniqueId().toString().equals(current.recipient())
            || actor.getLocation().distanceSquared(peer.getLocation()) > 9) continue;
        VillagerWorker worker = plugin.worker(current.recipient());
        if (worker == null || !worker.villageId().equals(village.id())) continue;
        Material type = Material.matchMaterial(current.material());
        int amount = board.amount(current, now);
        String material = current.material();
        amount =
            Math.min(
                amount,
                worker.currentRequests().entrySet().stream()
                    .filter(e -> DeliveryBoard.matches(e.getKey(), material))
                    .mapToInt(Map.Entry::getValue)
                    .max()
                    .orElse(0));
        if (type == null || amount <= 0) break;
        Map<String, Integer> beforeActor = InventoryOps.summary(actor.getInventory());
        Map<String, Integer> beforePeer = InventoryOps.summary(peer.getInventory());
        int moved =
            InventoryOps.transfer(actor.getInventory(), peer.getInventory(), Set.of(type), amount);
        if (moved > 0) {
          TransferReceipts.record(
              plugin,
              village,
              id,
              "delivery",
              id,
              current.recipient(),
              beforeActor,
              actor.getInventory(),
              beforePeer,
              peer.getInventory());
          village.remember(id, "Delivered " + moved + " " + type + " for " + current.task(), true);
          village.remember(
              current.recipient(), "Received " + moved + " " + type + " for current work", true);
          village.knowledge().clear("resource:" + type.name());
          if (type.name().endsWith("_LOG")) village.knowledge().clear("resource:LOG");
          actor.lookAt(peer);
          actor.swingMainHand();
        } else
          plugin.debug(
              village.id(),
              id,
              "delivery_blocked",
              Map.of("reason", "recipient_inventory_full", "delivery", current));
        board.received(current, moved);
        current = null;
        navigation.stop();
        next = now + 2000;
        return true;
      }
    // The ordinary work radius can stop beyond physical handoff range. Account for each
    // entity's fractional position inside its block when selecting the courier's endpoint.
    if (target != null) navigation.walkExact(target.position(), 2, now);
    return true;
  }
}
