package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import dev.civilizations.navigation.NavigationMap;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Villager;

/**
 * Recovery prerequisites reuse normal work executors without replacing the retained village job.
 */
final class RecoveryTaskActions {
  record Result(boolean ready, boolean changed, String failure, Map<String, Object> evidence) {}

  private final CivilizationsPlugin plugin;
  private final Villager actor;
  private final Settlement village;
  private final WorkerNavigation navigation;
  private ToolActions tools;
  private final GatheringActions gathering;
  private final WorkPose pose;
  private final BuildingActions mining;
  private String problem = "";
  private boolean mined;

  RecoveryTaskActions(
      CivilizationsPlugin plugin, Villager actor, Settlement village, WorkerNavigation navigation) {
    this.plugin = plugin;
    this.actor = actor;
    this.village = village;
    this.navigation = navigation;
    tools = new ToolActions(plugin, actor, village, navigation, (now, reason) -> problem = reason);
    gathering =
        new GatheringActions(
            plugin,
            actor,
            village,
            navigation,
            new RecoveryPolicy(System.currentTimeMillis(), 30_000),
            (now, reason) -> problem = reason);
    pose = new WorkPose(plugin, actor);
    mining =
        new BuildingActions(
            plugin,
            actor,
            (now, reason) -> problem = reason,
            now -> mined = true,
            (now, obstruction) -> problem = "unexpected_mining_placement");
  }

  void reset() {
    gathering.reset();
    pose.reset();
    problem = "";
  }

  void shareTools(ToolActions tools) {
    this.tools = Objects.requireNonNull(tools);
  }

  Result acquire(String material, long now) {
    Map<String, Integer> before = inventory();
    problem = "";
    Material item = Material.getMaterial(material);
    if (item == null || !item.isItem() || item.isAir())
      return result(false, before, "acquire_unknown_item: " + material, Map.of());
    if (!acquired(material, before)) {
      ToolActions.Preparation preparation = tools.prepareItem(material, now, here());
      if (problem.isEmpty() && !preparation.gather().isEmpty()) gather(preparation.gather(), now);
    }
    boolean ready = problem.isEmpty() && acquired(material, inventory());
    return result(
        ready,
        before,
        problem,
        Map.of(
            "requested_item",
            material,
            "minimum_held",
            1,
            "completion_basis",
            "actual_inventory_contains_requested_item",
            "already_held",
            acquired(material, before)));
  }

  static boolean acquired(String material, Map<String, Integer> inventory) {
    return inventory.getOrDefault(material, 0) > 0;
  }

  private void gather(String material, long now) {
    if (gathering.gather(material, now, here()))
      problem = "acquire_source_unavailable: " + material;
  }

  Result mine(Pos target, String expected, NavigationMap map, Pos buildSite, long now) {
    Map<String, Integer> before = inventory();
    problem = "";
    mined = false;
    String observed = map.cell(target).material();
    if (!expected.equals(observed))
      return result(
          false,
          before,
          "mine_observed_material_mismatch: expected=" + expected + ", observed=" + observed,
          Map.of("target", target));
    if (!Bukkit.isOwnedByCurrentRegion(location(target), 2)
        || here().distance2(target) > WorkPose.feetEnvelope(actor.getEyeHeight())) {
      navigation.walkWork(here(), target, WorkPose.feetEnvelope(actor.getEyeHeight()), now);
      return result(false, before, problem, Map.of("target", target));
    }
    Block block = location(target).getBlock();
    String original = block.getBlockData().getAsString();
    String failure = mineFailure(target, expected, buildSite, block, now);
    if (!failure.isEmpty())
      return result(
          false,
          before,
          failure,
          Map.of("target", target, "expected_material", expected, "block_before", original));
    if (!MiningTools.has(actor.getInventory(), expected)) {
      ToolActions.Preparation preparation =
          tools.prepare(ToolRecipes.required(expected), now, here());
      if (problem.isEmpty() && !preparation.gather().isEmpty()) gather(preparation.gather(), now);
      return result(
          false, before, problem, Map.of("target", target, "phase", "prepare_mining_tool"));
    }
    if (!pose.accessible(block)) {
      pose.reset();
      navigation.walkWork(here(), target, WorkPose.feetEnvelope(actor.getEyeHeight()), now);
      return result(false, before, problem, Map.of("target", target));
    }
    if (!pose.ready(block, now)) return result(false, before, "", Map.of("target", target));
    if (!plugin.mayChange(actor, block, "MINE"))
      return result(false, before, "mine_cancelled_by_protection", Map.of("target", target));
    // Protection listeners may mutate either the block or its surroundings before returning.
    failure = mineFailure(target, expected, buildSite, block, now);
    if (!original.equals(block.getBlockData().getAsString()) || !failure.isEmpty())
      return result(
          false,
          before,
          "mine_changed_during_permission_event: " + failure,
          Map.of("target", target));
    BlockLessons.remember(plugin, actor, block, "recovery_mining_observation");
    mining.execute(
        new Job(Job.Kind.MINE, "recovery", target, here(), "", expected, ""), block, now);
    Map<String, Object> evidence =
        Map.of(
            "target", target,
            "expected_material", expected,
            "block_before", original,
            "block_after", block.getBlockData().getAsString(),
            "completion_basis", "actual_block_removed_by_mining_executor");
    var result = result(mined && block.getType().isAir(), before, problem, evidence);
    return new Result(
        result.ready(), mined || result.changed(), result.failure(), result.evidence());
  }

  private String mineFailure(Pos target, String expected, Pos buildSite, Block block, long now) {
    if (!block.getType().name().equals(expected))
      return "mine_live_material_changed: expected="
          + expected
          + ", actual="
          + block.getBlockData().getAsString();
    if (!BlockRules.natural(block)) return "mine_target_not_supported_natural_terrain";
    boolean ownSite =
        target.equals(buildSite)
            && village.ownsBuildSite(target, actor.getUniqueId().toString(), now);
    if (village.gatherProtected(target) && !ownSite || plugin.playerProtected(village, target))
      return "mine_target_reserved_or_protected";
    if (!BlockRules.dry(block)) return "mine_would_expose_fluid: " + SourceConditions.fluids(block);
    if (!BlockRules.safeMining(block))
      return "mine_has_falling_block_or_container: above=" + block.getRelative(0, 1, 0).getType();
    if (target.x() == here().x() && target.z() == here().z() && target.y() < here().y())
      return "mine_would_remove_current_support";
    Terrain terrain =
        new Terrain() {
          public int height(int x, int z) {
            return 0;
          }

          public boolean available(int x, int z) {
            return Bukkit.isOwnedByCurrentRegion(
                new Location(actor.getWorld(), x, target.y(), z), 1);
          }

          public String type(Pos p) {
            return location(p).getBlock().getType().name();
          }
        };
    Set<Pos> protectedBlocks = new HashSet<>(village.layoutOccupancy());
    if (ownSite) protectedBlocks.remove(target);
    if (NavigationTerrain.architectureNear(terrain, target, protectedBlocks))
      return "mine_target_supports_or_borders_structure";
    return "";
  }

  private Result result(
      boolean ready, Map<String, Integer> before, String failure, Map<String, Object> details) {
    Map<String, Integer> after = inventory();
    Map<String, Object> evidence = new LinkedHashMap<>(details);
    evidence.put("inventory_before", before);
    evidence.put("inventory_after", after);
    return new Result(ready, !before.equals(after), failure, Map.copyOf(evidence));
  }

  private Map<String, Integer> inventory() {
    return InventoryOps.summary(actor.getInventory());
  }

  private Pos here() {
    Location p = actor.getLocation();
    return new Pos(p.getBlockX(), p.getBlockY(), p.getBlockZ());
  }

  private Location location(Pos p) {
    return new Location(actor.getWorld(), p.x() + .5, p.y(), p.z() + .5);
  }
}
