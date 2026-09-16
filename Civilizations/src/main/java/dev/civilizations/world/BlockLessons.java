package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.Settlement;
import dev.coreai.TerrainRuleBook;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Villager;

/**
 * Ordinary observation learns physical block facts; it never grants permission to remove a block.
 */
public final class BlockLessons {
  private final CivilizationsPlugin plugin;
  private final Villager actor;
  private final Settlement village;
  private final Set<String> reportedReuse = new HashSet<>();
  private long nextObservation;

  public BlockLessons(CivilizationsPlugin plugin, Villager actor, Settlement village) {
    this.plugin = plugin;
    this.actor = actor;
    this.village = village;
  }

  public void observe(long now) {
    if (now < nextObservation || plugin.experiments() == null) return;
    nextObservation = now + 1000;
    plugin.experiments().saveLessons();
    var at = actor.getLocation();
    var seen = new HashSet<String>();
    for (int dx = -2; dx <= 2; dx++)
      for (int dz = -2; dz <= 2; dz++)
        for (int dy = -1; dy <= 2; dy++) {
          var location = at.clone().add(dx, dy, dz);
          if (location.getBlockY() < at.getWorld().getMinHeight()
              || location.getBlockY() >= at.getWorld().getMaxHeight()
              || !Bukkit.isOwnedByCurrentRegion(location, 1)) continue;
          Block block = location.getBlock();
          // Solid earth/buildings keep the normal salvage/support checks. Observe unusual ground
          // cover where snapshot material names alone cannot tell us about live collision.
          if (block.getType().isAir() || block.getType().isSolid()) continue;
          String material = block.getType().name(), state = block.getBlockData().getAsString();
          String key = TerrainRuleBook.key(material, state);
          if (!seen.add(key)) continue;
          var known =
              plugin.experiments().rules().rule(actor.getUniqueId().toString(), material, state);
          if (known == null) {
            remember(plugin, actor, block, "nearby_observation");
          } else if (reportedReuse.add(key)) {
            plugin.debug(
                village.id(),
                actor.getUniqueId().toString(),
                "block_lesson_reused",
                Map.of(
                    "material",
                    material,
                    "state",
                    state,
                    "category",
                    known.category(),
                    "learned_at",
                    known.learnedAt(),
                    "basis",
                    "same observed block state"));
          }
        }
  }

  /** Call before removal/replanting, while the original physical facts are still observable. */
  public static void remember(
      CivilizationsPlugin plugin, Villager actor, Block block, String source) {
    if (plugin.experiments() == null || block.getType().isAir()) return;
    var facts = BlockObservation.capture(block);
    String category = category(facts);
    if (category == null) return;
    if (plugin.experiments().remember(facts, category, source, "host:live-physical-observation"))
      plugin.debug(
          "",
          actor.getUniqueId().toString(),
          "block_lesson_learned",
          Map.of(
              "facts",
              facts,
              "category",
              category,
              "source",
              source,
              "basis",
              "live physical observation; independent of task outcome"));
  }

  public static String category(TerrainRuleBook.Facts facts) {
    if (!facts.observed()
        || facts.dangerous()
        || facts.fluid()
        || facts.container()
        || facts.material().equals("UNKNOWN")) return null;
    return facts.passable() && !facts.material().equals("COBWEB")
        ? "PASSABLE"
        : facts.removable() && !facts.solid() ? "CLEARABLE" : "OBSTACLE";
  }
}
