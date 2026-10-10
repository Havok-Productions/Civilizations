package dev.civilizations.playerai;

import dev.civilizations.core.Pos;
import dev.civilizations.world.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Mannequin;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.util.BoundingBox;

/** First body adapter: conservative local harvesting, hand crafting and paid placement. */
final class MannequinActions implements PlayerAi.Actions {
  private final PlayerAiCoordinator coordinator;
  private final Mannequin body;
  private final RegionSnapshots snapshots;
  private final PlayerAiNavigation navigation;
  private final Consumer<PlayerAi.State> save;
  private final int radius;
  private final Map<Pos, Long> rejected = new HashMap<>();
  private CompletableFuture<Terrain> survey;
  private Pos source;
  private String sourceMaterial;
  private Pos stepAside;

  MannequinActions(
      PlayerAiCoordinator coordinator,
      Mannequin body,
      RegionSnapshots snapshots,
      Executor executor,
      int radius,
      Consumer<PlayerAi.State> save) {
    this.coordinator = coordinator;
    this.body = body;
    this.snapshots = snapshots;
    this.radius = radius;
    this.save = save;
    navigation = new PlayerAiNavigation(body, snapshots, executor, radius);
  }

  private Terrain terrain() {
    if (survey == null)
      survey =
          snapshots.capture(body.getWorld(), PlayerAiNavigation.pos(body.getLocation()), radius);
    if (!survey.isDone()) return null;
    Terrain result = survey.join();
    survey = null;
    return result;
  }

  public PlayerAi.Outcome findCamp() {
    Terrain terrain = terrain();
    if (terrain == null) return PlayerAi.Outcome.waiting("Surveying a starter campsite");
    Pos here = PlayerAiNavigation.pos(body.getLocation());
    for (int distance : new int[] {4, 7, 10})
      for (int[] direction : new int[][] {{1, 0}, {0, 1}, {-1, 0}, {0, -1}}) {
        Pos origin = here.add(direction[0] * distance, 0, direction[1] * distance);
        origin = new Pos(origin.x(), terrain.groundHeight(origin.x(), origin.z()) + 1, origin.z());
        if (Math.abs(origin.y() - here.y()) > 3 || !flatCamp(terrain, origin)) continue;
        if (coordinator.reserve(body.getUniqueId(), body.getWorld().getUID(), origin))
          return PlayerAi.Outcome.done(null, origin);
      }
    return PlayerAi.Outcome.blocked(
        "No dry, flat, empty 5x5 campsite nearby; move to open ground before spawning");
  }

  static boolean flatCamp(Terrain terrain, Pos origin) {
    for (int x = -1; x <= 3; x++)
      for (int z = -1; z <= 3; z++) {
        Pos p = origin.add(x, 0, z);
        if (!terrain.available(p.x(), p.z()) || !terrain.natural(p.add(0, -1, 0))) return false;
        for (int y = 0; y < 4; y++)
          if (!Set.of("AIR", "CAVE_AIR", "VOID_AIR").contains(terrain.type(p.add(0, y, 0))))
            return false;
      }
    return true;
  }

  public PlayerAi.Outcome gatherWood() {
    long now = System.currentTimeMillis();
    rejected.entrySet().removeIf(e -> e.getValue() <= now);
    if (source == null) {
      Terrain terrain = terrain();
      if (terrain == null) return PlayerAi.Outcome.waiting("Looking for nearby natural trees");
      Pos here = PlayerAiNavigation.pos(body.getLocation());
      for (Pos root : SurfaceResources.observe(terrain, here, radius, "LOG").sources()) {
        if (!PlayerAi.log(terrain.type(root)) || !leaves(terrain, root)) continue;
        // Harvest the lower trunk top-down. The root remains evidence until the last log.
        for (int y = 2; y >= 0; y--) {
          Pos candidate = root.add(0, y, 0);
          if (!terrain.type(candidate).equals(terrain.type(root))
              || rejected.containsKey(candidate)) continue;
          source = candidate;
          sourceMaterial = terrain.type(candidate);
          break;
        }
        if (source != null) break;
      }
      if (source == null)
        return PlayerAi.Outcome.blocked(
            "No usable natural tree within loaded search range (" + radius + " blocks)");
    }
    var approach = navigation.approach(source);
    if (approach.result() == PlayerAi.Result.BLOCKED) return reject(approach.detail());
    if (approach.result() != PlayerAi.Result.DONE) return approach;
    if (!owns(source)) return reject("Tree is outside the owned region");
    Block block = at(source);
    if (!block.getType().name().equals(sourceMaterial)
        || !BlockRules.tree(block)
        || !BlockRules.dry(block)
        || !visible(block)
        || coordinator.protectedBlock(block))
      return reject("Tree changed, is protected, wet, or outside visible reach");
    String before = block.getBlockData().getAsString();
    if (!mayChange(block, Material.AIR, "HARVEST")
        || !before.equals(block.getBlockData().getAsString()))
      return reject("Harvest denied or tree changed during protection check");
    // Only ordinary logs are admitted: a bare-hand log harvest yields exactly one matching log.
    String harvested = block.getType().name();
    block.setType(Material.AIR, false);
    if (!block.getType().isAir()) return reject("Log removal did not persist");
    body.swingMainHand();
    source = null;
    return PlayerAi.Outcome.done(harvested, null);
  }

  private static boolean leaves(Terrain terrain, Pos root) {
    for (int y = 1; y <= 8; y++)
      for (int x = -2; x <= 2; x++)
        for (int z = -2; z <= 2; z++)
          if (terrain.type(root.add(x, y, z)).endsWith("_LEAVES")) return true;
    return false;
  }

  private PlayerAi.Outcome reject(String reason) {
    if (source != null) {
      if (rejected.size() >= 64) rejected.clear();
      rejected.put(source, System.currentTimeMillis() + 60_000);
    }
    source = null;
    navigation.halt();
    return PlayerAi.Outcome.blocked(reason);
  }

  public PlayerAi.Outcome place(StarterCamp.Placement placement, String material) {
    Pos target = placement.position();
    if (stepAside != null) {
      var aside = navigation.approach(stepAside, 0);
      if (aside.result() != PlayerAi.Result.DONE) return aside;
      stepAside = null;
    }
    var approach = navigation.approach(placement.stand(), 0);
    if (approach.result() != PlayerAi.Result.DONE) return approach;
    if (!owns(target)) return PlayerAi.Outcome.blocked("Build site is outside the owned region");
    Block block = at(target);
    // On resume, only a previously completed receipt advances progress. Existing blocks at the
    // next position are treated as a changed site, never as permission to replace them.
    if (!block.getType().isAir() || !BlockRules.dry(block) || coordinator.protectedBlock(block))
      return PlayerAi.Outcome.blocked(
          "Build site changed, is protected, or became wet at " + target.key());
    if (!visible(block)) return PlayerAi.Outcome.blocked("Build target is out of visible reach");
    var box =
        new BoundingBox(
            target.x(), target.y(), target.z(), target.x() + 1, target.y() + 1, target.z() + 1);
    if (body.getBoundingBox().overlaps(box)) {
      Pos here = PlayerAiNavigation.pos(body.getLocation());
      for (int[] offset : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
        Pos stand = here.add(offset[0], 0, offset[1]);
        if (!owns(stand)) continue;
        Block feet = at(stand);
        if (feet.isPassable()
            && feet.getRelative(0, 1, 0).isPassable()
            && feet.getRelative(0, -1, 0).getType().isOccluding()
            && BlockRules.dry(feet)) {
          stepAside = stand;
          return PlayerAi.Outcome.waiting("Stepping out of the construction target");
        }
      }
      return PlayerAi.Outcome.blocked("No safe space to step out of construction target");
    }
    if (!body.getWorld()
        .getNearbyEntities(box, e -> e instanceof org.bukkit.entity.LivingEntity)
        .isEmpty()) return PlayerAi.Outcome.blocked("An entity occupies the build target");
    Material type = Material.valueOf(material);
    String before = block.getBlockData().getAsString();
    if (!mayChange(block, type, "PLACE") || !before.equals(block.getBlockData().getAsString()))
      return PlayerAi.Outcome.blocked("Placement denied or site changed during protection check");
    block.setType(type, false);
    if (block.getType() != type) return PlayerAi.Outcome.blocked("Placement did not persist");
    body.swingMainHand();
    return PlayerAi.Outcome.done(null, target);
  }

  private boolean owns(Pos p) {
    return p.y() > body.getWorld().getMinHeight()
        && p.y() + 9 < body.getWorld().getMaxHeight()
        && PlayerAiNavigation.ownedLoaded(body.getWorld(), p, 2);
  }

  private Block at(Pos p) {
    return body.getWorld().getBlockAt(p.x(), p.y(), p.z());
  }

  private boolean visible(Block block) {
    var eye = body.getEyeLocation();
    var center = block.getLocation().add(.5, .5, .5);
    var ray = center.toVector().subtract(eye.toVector());
    double distance = ray.length();
    if (distance > 4 || !body.getWorld().getWorldBorder().isInside(center)) return false;
    if (distance < .01) return true;
    var hit =
        body.getWorld()
            .rayTraceBlocks(eye, ray.normalize(), distance, FluidCollisionMode.ALWAYS, false);
    return hit == null || block.equals(hit.getHitBlock());
  }

  private boolean mayChange(Block block, Material material, String action) {
    var vanilla = new EntityChangeBlockEvent(body, block, material.createBlockData());
    Bukkit.getPluginManager().callEvent(vanilla);
    if (vanilla.isCancelled()) return false;
    var event = new PlayerAiWorkEvent(body, block, action);
    Bukkit.getPluginManager().callEvent(event);
    return !event.isCancelled() && body.isValid();
  }

  public void halt() {
    navigation.halt();
    if (survey != null) survey.cancel(false);
    survey = null;
  }

  public void checkpoint(PlayerAi.State state) {
    save.accept(state);
  }
}
