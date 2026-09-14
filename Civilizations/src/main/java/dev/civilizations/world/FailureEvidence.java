package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Villager;

/** Reports observable causes to recovery; unavailable regions remain explicitly unknown. */
public final class FailureEvidence {
  private FailureEvidence() {}

  public static Map<String, Object> inspect(
      CivilizationsPlugin plugin,
      Villager actor,
      Settlement village,
      Job job,
      String reason,
      Map<String, Integer> requested,
      long now) {
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("reason", reason);
    Location actorAt = actor.getLocation();
    report.put("position", new Pos(actorAt.getBlockX(), actorAt.getBlockY(), actorAt.getBlockZ()));
    report.put("inventory", InventoryOps.summary(actor.getInventory()));
    report.put("requested", requested);
    report.put("tool_tier", ToolRecipes.tier(InventoryOps.summary(actor.getInventory())));
    report.put("stockpile", village.stock());
    report.put("stock_age_ms", village.stockAge(now));
    Map<String, Integer> sites = new LinkedHashMap<>();
    requested.keySet().forEach(m -> sites.put(m, plugin.resources(village.id(), m).size()));
    report.put("known_source_counts", sites);
    if (job != null) {
      report.put("job", job.id);
      report.put("project", job.project);
      report.put("target", job.target);
      report.put(
          "route_temporarily_blocked",
          village.knowledge().blocked("route:" + job.stand.key(), now));
      report.put("player_protected", plugin.playerProtected(village, job.target));
      Location p = new Location(actor.getWorld(), job.target.x(), job.target.y(), job.target.z());
      boolean owned = Bukkit.isOwnedByCurrentRegion(p, 1);
      report.put("target_observed", owned);
      if (owned) {
        report.put("actual_block", p.getBlock().getType().name());
        report.put("dry", BlockRules.dry(p.getBlock()));
      }
    }
    return Map.copyOf(report);
  }
}
