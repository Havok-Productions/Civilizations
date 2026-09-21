package dev.civilizations.world;

import dev.civilizations.core.*;
import dev.civilizations.navigation.*;
import java.util.*;
import org.bukkit.Material;

/** Classifies snapshot blocks without touching live world data. */
public final class NavigationTerrain {
  private NavigationTerrain() {}

  private static final Set<String> SOIL =
      Set.of("DIRT", "GRASS_BLOCK", "ROOTED_DIRT", "PODZOL", "COARSE_DIRT", "MYCELIUM");

  public static NavigationMap capture(
      Terrain terrain, Pos center, int radius, Set<Pos> protectedBlocks) {
    return capture(terrain, center, radius, protectedBlocks, null, "");
  }

  public static NavigationMap capture(
      Terrain terrain,
      Pos center,
      int radius,
      Set<Pos> protectedBlocks,
      dev.coreai.TerrainRuleBook rules,
      String worker) {
    return capture(terrain, center, radius, 10, protectedBlocks, rules, worker);
  }

  public static NavigationMap capture(
      Terrain terrain,
      Pos center,
      int radius,
      int vertical,
      Set<Pos> protectedBlocks,
      dev.coreai.TerrainRuleBook rules,
      String worker) {
    Map<Pos, NavigationMap.Cell> cells = new HashMap<>();
    var learned =
        rules == null
            ? java.util.Map.<String, dev.coreai.TerrainRuleBook.Rule>of()
            : rules.view(worker);
    for (int x = center.x() - radius; x <= center.x() + radius; x++)
      for (int z = center.z() - radius; z <= center.z() + radius; z++)
        for (int y = center.y() - vertical - 1; y <= center.y() + vertical + 2; y++) {
          Pos p = new Pos(x, y, z);
          String type = terrain.type(p);
          Material material = Material.matchMaterial(type);
          String state = terrain.blockData(p);
          var rule = learned.get(dev.coreai.TerrainRuleBook.key(type, state));
          NavigationMap.Kind kind;
          if (type.equals("UNKNOWN") || material == null) kind = NavigationMap.Kind.UNKNOWN;
          else if (BlockObservation.dangerous(type, state)) kind = NavigationMap.Kind.HAZARD;
          else if (terrain.fluid(p) || state != null && state.contains("waterlogged=true"))
            kind = NavigationMap.Kind.FLUID;
          else if (rule != null && !rule.facts().solid())
            kind = learnedKind(rule, protectedBlocks.contains(p));
          else if (type.equals("COBWEB")) kind = NavigationMap.Kind.OBSTACLE;
          else if (material.isAir()
              || terrain.clear(p)
              || Set.of(
                      "TORCH",
                      "WALL_TORCH",
                      "REDSTONE_WIRE",
                      "RAIL",
                      "LEAF_LITTER",
                      "PINK_PETALS",
                      "WILDFLOWERS")
                  .contains(type)) kind = NavigationMap.Kind.AIR;
          else if ((type.endsWith("_DOOR") && !type.startsWith("IRON_"))
              || type.endsWith("_FENCE_GATE")) kind = NavigationMap.Kind.OPENABLE;
          else if (!protectedBlocks.contains(p)
              && salvageable(terrain, p)
              && terrain.dry(p)
              && !architectureNear(terrain, p, protectedBlocks)) kind = NavigationMap.Kind.SOFT;
          else if (material.isSolid()) kind = NavigationMap.Kind.SOLID;
          else kind = NavigationMap.Kind.UNCLASSIFIED;
          String removal;
          if (kind == NavigationMap.Kind.UNKNOWN) removal = "needs_observation";
          else if (protectedBlocks.contains(p)) removal = "protected_or_reserved";
          else if (kind == NavigationMap.Kind.HAZARD) removal = "hazard";
          else if (kind == NavigationMap.Kind.FLUID) removal = "fluid";
          else if (kind == NavigationMap.Kind.SOFT || kind == NavigationMap.Kind.CLEARABLE)
            removal = "CLEAR_within_reach_live_checks_and_retained_drops";
          else if (kind == NavigationMap.Kind.AIR) removal = "no_clearance_needed";
          else if (kind == NavigationMap.Kind.OPENABLE) removal = "open_door_or_gate";
          else if (architectureNear(terrain, p, protectedBlocks)) removal = "structure_or_support";
          else if (material.getHardness() < 0) removal = "unbreakable";
          else if (material.isSolid()) removal = "requires_mining_task_and_suitable_tool_not_CLEAR";
          else removal = "CLASSIFY_live_physics_before_clearance";
          cells.put(p, new NavigationMap.Cell(type, kind, state, removal));
        }
    return new NavigationMap(center, radius, vertical, cells);
  }

  public static NavigationMap.Kind learnedKind(
      dev.coreai.TerrainRuleBook.Rule rule, boolean protectedBlock) {
    // Removability and collision are independent: learning to clear grass must not
    // turn the worker's current, walkable cell into a blocked route start.
    if (rule.facts().passable() && !rule.facts().material().equals("COBWEB"))
      return NavigationMap.Kind.AIR;
    return switch (rule.category()) {
      case "PASSABLE" -> NavigationMap.Kind.AIR;
      case "CLEARABLE" ->
          protectedBlock ? NavigationMap.Kind.OBSTACLE : NavigationMap.Kind.CLEARABLE;
      default ->
          rule.facts().solid()
              ? NavigationMap.Kind.SOLID
              : rule.facts().removable() && !protectedBlock
                  ? NavigationMap.Kind.CLEARABLE
                  : NavigationMap.Kind.OBSTACLE;
    };
  }

  public static boolean salvageable(Terrain t, Pos p) {
    String type = t.type(p);
    if (SOIL.contains(type)) {
      String above = t.type(p.add(0, 1, 0));
      return SOIL.contains(above) || t.clear(p.add(0, 1, 0));
    }
    if (type.endsWith("_LEAVES"))
      return !String.valueOf(t.blockData(p)).contains("persistent=true");
    if (!type.endsWith("_LOG") || type.startsWith("STRIPPED_")) return false;
    Pos root = p;
    for (int i = 0; i < 8 && t.type(root.add(0, -1, 0)).endsWith("_LOG"); i++)
      root = root.add(0, -1, 0);
    if (!SOIL.contains(t.type(root.add(0, -1, 0)))) return false;
    for (int y = 0; y <= 8; y++)
      for (int x = -2; x <= 2; x++)
        for (int z = -2; z <= 2; z++)
          if (t.type(p.add(x, y, z)).endsWith("_LEAVES")
              && !String.valueOf(t.blockData(p.add(x, y, z))).contains("persistent=true"))
            return true;
    return false;
  }

  public static boolean architectureNear(Terrain t, Pos p, Set<Pos> protectedBlocks) {
    for (int dx = -1; dx <= 1; dx++)
      for (int dz = -1; dz <= 1; dz++)
        for (int dy = -1; dy <= 1; dy++) {
          Pos q = p.add(dx, dy, dz);
          // A neighbouring wall does not make loose soil architectural. Its foundation and
          // blocks supporting a structure remain protected.
          if (dev.civilizations.core.SiteMaterials.soil(t.type(p))
              && (dx != 0 || dz != 0 || dy < 0)) continue;
          String type = t.type(q);
          if (protectedBlocks.contains(q)
              || type.endsWith("_PLANKS")
              || type.endsWith("_BRICKS")
              || type.endsWith("_GLASS")
              || type.endsWith("_BED")
              || type.contains("CHEST")
              || type.equals("CRAFTING_TABLE")
              || type.endsWith("_DOOR")
              || type.endsWith("_FENCE")
              || type.endsWith("_FENCE_GATE")) return true;
        }
    return false;
  }
}
