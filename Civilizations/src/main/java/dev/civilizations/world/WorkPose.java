package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.Pos;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Villager;

/** Turn toward a specific block, let the pose be visible, then verify facing before work. */
public final class WorkPose {
  static final double EYE_REACH = 5;
  private final CivilizationsPlugin plugin;
  private final Villager actor;
  private Pos target;
  private long readyAt;

  public WorkPose(CivilizationsPlugin plugin, Villager actor) {
    this.plugin = plugin;
    this.actor = actor;
  }

  public boolean accessible(Block block) {
    Location eye = actor.getEyeLocation(), center = block.getLocation().add(.5, .5, .5);
    var delta = center.toVector().subtract(eye.toVector());
    double distance = delta.length();
    if (distance > EYE_REACH) return false;
    if (distance < .001) return true;
    var hit =
        actor
            .getWorld()
            .rayTraceBlocks(
                eye,
                delta.multiply(1 / distance),
                distance,
                org.bukkit.FluidCollisionMode.NEVER,
                true);
    return hit == null || block.equals(hit.getHitBlock());
  }

  public boolean ready(Block block, long now) {
    Pos next = new Pos(block.getX(), block.getY(), block.getZ());
    Location center = block.getLocation().add(.5, .5, .5);
    var delta = center.toVector().subtract(actor.getEyeLocation().toVector());
    if (!accessible(block)) return false;
    if (delta.lengthSquared() < .000001) return false;
    var direction = delta.clone().normalize();
    actor.getPathfinder().stopPathfinding();
    Location facing = actor.getLocation().setDirection(direction);
    actor.setRotation(facing.getYaw(), facing.getPitch());
    actor.lookAt(center, 90, 90);
    double alignment = actor.getEyeLocation().getDirection().dot(direction);
    if (!next.equals(target)) {
      target = next;
      readyAt = now + WorkerTuning.value(plugin, actor, "construction.face_ms");
      return false;
    }
    if (now < readyAt || alignment < .90) return false;
    plugin.debug(
        "",
        actor.getUniqueId().toString(),
        "work_pose",
        Map.of(
            "target",
            target,
            "position",
            new Pos(
                actor.getLocation().getBlockX(),
                actor.getLocation().getBlockY(),
                actor.getLocation().getBlockZ()),
            "facing_dot",
            alignment,
            "eye_distance",
            delta.length(),
            "verified",
            true));
    return true;
  }

  public void reset() {
    target = null;
    readyAt = 0;
  }
}
