package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;

/** A cooperative worker yields without abandoning its own claim or editing another entity. */
final class YieldActions {
  private final CivilizationsPlugin plugin;
  private final Villager actor;
  private final Settlement village;
  private final WorkerNavigation navigation;
  private YieldBoard.Request active;
  private final Set<Pos> rejected = new HashSet<>();
  private Pos stand;
  private long retry;

  YieldActions(
      CivilizationsPlugin plugin, Villager actor, Settlement village, WorkerNavigation navigation) {
    this.plugin = plugin;
    this.actor = actor;
    this.village = village;
    this.navigation = navigation;
  }

  void request(PlacementSpace.Obstruction obstruction, Job job, long now) {
    if (!village.members().contains(obstruction.entity().toString())) return;
    List<Pos> spaces = new ArrayList<>(List.of(job.target, obstruction.block()));
    village
        .yielding()
        .request(
            obstruction.entity().toString(),
            new YieldBoard.Request(actor.getUniqueId().toString(), job.id, spaces, now + 5000),
            now);
  }

  boolean tick(long now) {
    var request = village.yielding().incoming(actor.getUniqueId().toString(), now);
    if (request == null
        || village.jobs().stream().noneMatch(j -> j.id.equals(request.task()) && !j.complete)) {
      if (active != null) {
        navigation.stop();
        active = null;
        stand = null;
        rejected.clear();
      }
      return false;
    }
    if (active == null || !active.task().equals(request.task())) {
      navigation.stop();
      stand = null;
      rejected.clear();
      retry = 0;
      plugin.debug(
          village.id(),
          actor.getUniqueId().toString(),
          "yield_requested",
          Map.of(
              "requester",
              request.requester(),
              "task",
              request.task(),
              "spaces",
              request.spaces(),
              "own_task_retained",
              true));
    }
    active = request;
    var spaces =
        request.spaces().stream()
            .map(p -> new BoundingBox(p.x(), p.y(), p.z(), p.x() + 1, p.y() + 1, p.z() + 1))
            .toList();
    if (spaces.stream().noneMatch(b -> b.clone().expand(.15).overlaps(actor.getBoundingBox()))) {
      actor.getPathfinder().stopPathfinding();
      return true; // Keep this space free until the builder finishes or lets the request expire.
    }
    if (now < retry) return true;
    if (stand == null) {
      Pos target = request.spaces().getFirst();
      stand =
          WorkPositions.choose(
              actor,
              target.add(1, 0, 0),
              target,
              21,
              rejected,
              p -> PlacementSpace.fits(actor, p, spaces));
    }
    if (stand != null) navigation.walkExact(stand, 0, now);
    else retry = now + 2000;
    return true;
  }

  boolean failed(long now, String reason) {
    if (active == null) return false;
    if (stand != null) rejected.add(stand);
    stand = null;
    retry = now + 1000;
    navigation.stop();
    plugin.debug(
        village.id(),
        actor.getUniqueId().toString(),
        "yield_route_deferred",
        Map.of("reason", reason, "own_task_retained", true));
    return true;
  }

  void cancel() {
    village.yielding().cancel(actor.getUniqueId().toString());
    active = null;
    stand = null;
    rejected.clear();
  }
}
