package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;

/** Physical pickup and conversation. Both entities must belong to this same region now. */
public final class NearbyWork {
  private final CivilizationsPlugin plugin;
  private final Villager actor;
  private final Settlement village;
  private final String id;
  private long next;
  private final Map<String, Long> heard = new LinkedHashMap<>();

  public NearbyWork(CivilizationsPlugin plugin, Villager actor, Settlement village) {
    this.plugin = plugin;
    this.actor = actor;
    this.village = village;
    id = actor.getUniqueId().toString();
  }

  public void tick(Map<String, Integer> requests, long now) {
    if (now < next || !Bukkit.isOwnedByCurrentRegion(actor.getLocation(), 1)) return;
    next = now + 1000;
    Map<String, Integer> initial = InventoryOps.summary(actor.getInventory());
    for (Entity nearby : actor.getNearbyEntities(4, 2, 4)) {
      if (!Bukkit.isOwnedByCurrentRegion(nearby) || !nearby.isValid()) continue;
      Map<String, Integer> outstanding =
          SupplyRequests.remaining(requests, initial, InventoryOps.summary(actor.getInventory()));
      double distance = actor.getLocation().distanceSquared(nearby.getLocation());
      if (nearby instanceof Item item && distance <= 4) pickup(item, outstanding);
      if (nearby instanceof Villager peer && distance <= 16) {
        VillagerWorker worker = plugin.worker(peer.getUniqueId().toString());
        if (worker == null || !worker.villageId().equals(village.id()) || peer.isSleeping())
          continue;
        String peerId = peer.getUniqueId().toString();
        List<Settlement.Memory> facts =
            village.memories(peerId).stream()
                .filter(
                    m ->
                        !m.result().startsWith("Heard ")
                            && !m.result().startsWith("Handed ")
                            && !m.result().startsWith("Received "))
                .toList();
        if (!facts.isEmpty()) {
          var fact = facts.getLast();
          if (fact.time() > heard.getOrDefault(peerId, 0L)) {
            heard.put(peerId, fact.time());
            village.remember(id, "Heard " + peerId.substring(0, 8) + ": " + fact.result(), false);
          }
        }
      }
    }
    while (heard.size() > 32) heard.remove(heard.keySet().iterator().next());
  }

  public static Set<Material> materials(String request) {
    if (request.equals("LOG")) return Tag.LOGS_THAT_BURN.getValues();
    Material m = Material.matchMaterial(request);
    return m == null ? Set.of() : Set.of(m);
  }

  private void pickup(Item item, Map<String, Integer> requests) {
    if (item.getPickupDelay() > 0 || item.getOwner() != null) return;
    ItemStack stack = item.getItemStack();
    int wanted =
        requests.entrySet().stream()
            .filter(e -> materials(e.getKey()).contains(stack.getType()))
            .mapToInt(Map.Entry::getValue)
            .max()
            .orElse(0);
    if (item.getThrower() != null)
      wanted =
          Math.max(
              wanted,
              DonationPolicy.desired(
                  stack.getType().name(),
                  InventoryOps.summary(actor.getInventory()),
                  village.mayShareSurplus(id)));
    if (wanted <= 0) return;
    ItemStack offered = stack.clone();
    offered.setAmount(Math.min(wanted, stack.getAmount()));
    if (!InventoryOps.canFit(actor.getInventory(), List.of(offered))) return;
    EntityPickupItemEvent event =
        new EntityPickupItemEvent(actor, item, stack.getAmount() - offered.getAmount());
    Bukkit.getPluginManager().callEvent(event);
    if (event.isCancelled() || !item.isValid() || !item.getItemStack().equals(stack)) return;
    if (!InventoryOps.canFit(actor.getInventory(), List.of(offered))) return;
    Map<String, Integer> beforeInventory = InventoryOps.summary(actor.getInventory());
    actor.getInventory().addItem(offered);
    if (offered.getAmount() == stack.getAmount()) item.remove();
    else {
      ItemStack rest = stack.clone();
      rest.setAmount(stack.getAmount() - offered.getAmount());
      item.setItemStack(rest);
    }
    plugin.debug(
        village.id(),
        id,
        "item_pickup",
        Map.of(
            "receipt",
            UUID.randomUUID().toString(),
            "item_entity",
            item.getUniqueId().toString(),
            "material",
            offered.getType().name(),
            "amount",
            offered.getAmount(),
            "drop_before",
            stack.getAmount(),
            "drop_after",
            stack.getAmount() - offered.getAmount(),
            "inventory_before",
            beforeInventory,
            "inventory_after",
            InventoryOps.summary(actor.getInventory()),
            "player_thrown",
            item.getThrower() != null));
    village.remember(id, "Picked up " + offered.getAmount() + " " + offered.getType(), true);
    village.knowledge().clear("resource:" + offered.getType().name());
  }
}
