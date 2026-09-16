package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.bukkit.entity.Villager;

/**
 * Searches successive observation tiles instead of repeating an empty scan at the same position.
 */
final class ResourceSurvey {
  private final java.util.function.Function<Pos, CompletableFuture<NavigationService.Plan>> capture;
  private final java.util.function.BiConsumer<String, Map<String, ?>> debug;
  private CompletableFuture<NavigationService.Plan> pending;
  private String resource = "";
  private Pos origin, center;
  private int tile;
  private long next;
  private boolean observed;
  private final Set<Pos> found = new HashSet<>();

  ResourceSurvey(CivilizationsPlugin plugin, Villager actor, Settlement village) {
    this(
        p ->
            plugin
                .navigation()
                .request(
                    actor.getWorld(), village, actor.getUniqueId().toString(), p, p, 0, "resource"),
        (type, data) -> plugin.debug(village.id(), actor.getUniqueId().toString(), type, data));
  }

  ResourceSurvey(
      java.util.function.Function<Pos, CompletableFuture<NavigationService.Plan>> capture,
      java.util.function.BiConsumer<String, Map<String, ?>> debug) {
    this.capture = capture;
    this.debug = debug;
  }

  List<Pos> search(String wanted, Pos at, long now) {
    if (!wanted.equals(resource) || origin == null || origin.horizontal2(at) > 6400) {
      resource = wanted;
      origin = at;
      tile = 0;
      found.clear();
      pending = null;
      next = 0;
      observed = false;
    }
    if (pending != null && pending.isDone()) {
      try {
        var map = pending.join().map();
        observed = !map.cell(center).material().equals("UNKNOWN");
        for (Material material : Material.values())
          if (MaterialSources.matches(wanted, material.name()))
            found.addAll(map.positions(material.name()));
        debug.accept(
            "resource_survey",
            Map.of(
                "resource",
                wanted,
                "origin",
                origin,
                "tile_center",
                center,
                "tile",
                tile - 1,
                "source_candidates",
                found.size(),
                "center_observed",
                !map.cell(center).material().equals("UNKNOWN"),
                "next_action",
                found.isEmpty() ? "observe next tile" : "approach and verify source"));
      } catch (RuntimeException error) {
        observed = false;
        debug.accept(
            "resource_survey_failure",
            Map.of("resource", wanted, "center", center, "reason", error.toString()));
      }
      pending = null;
      next = now + 3000;
    }
    if (pending == null && found.isEmpty() && now >= next) {
      center = tileCenter(origin, tile++, 20);
      pending = capture.apply(center);
    }
    return List.copyOf(found);
  }

  void exhausted(Pos p) {
    found.remove(p);
  }

  boolean awaitingObservation() {
    return pending != null || !observed;
  }

  static Pos tileCenter(Pos origin, int index, int spacing) {
    if (index == 0) return origin;
    int ring = (int) Math.ceil((Math.sqrt(index + 1) - 1) / 2), side = 2 * ring;
    int offset = index - (2 * ring - 1) * (2 * ring - 1), edge = offset / side, n = offset % side;
    int x =
        switch (edge) {
          case 0 -> -ring + n;
          case 1 -> ring;
          case 2 -> ring - n;
          default -> -ring;
        };
    int z =
        switch (edge) {
          case 0 -> -ring;
          case 1 -> -ring + n;
          case 2 -> ring;
          default -> ring - n;
        };
    return new Pos(
        Math.addExact(origin.x(), Math.multiplyExact(x, spacing)),
        origin.y(),
        Math.addExact(origin.z(), Math.multiplyExact(z, spacing)));
  }
}
