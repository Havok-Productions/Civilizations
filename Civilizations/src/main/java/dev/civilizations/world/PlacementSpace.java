package dev.civilizations.world;

import dev.civilizations.core.Job;
import dev.civilizations.core.Pos;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.*;
import org.bukkit.util.BoundingBox;

/** Collision checks against the proposed shape, without temporarily placing it in the world. */
public final class PlacementSpace {
  private PlacementSpace() {}

  public record Obstruction(UUID entity, String type, Pos block) {}

  static BlockData data(Job job) {
    return job.blockData == null
        ? Material.valueOf(job.material).createBlockData()
        : Bukkit.createBlockData(job.blockData);
  }

  private static Map<Block, BlockData> parts(Block block, BlockData data) {
    Map<Block, BlockData> parts = new LinkedHashMap<>();
    parts.put(block, data);
    if (data instanceof Bed bed) {
      Bed head = (Bed) data.clone();
      head.setPart(Bed.Part.HEAD);
      parts.put(block.getRelative(bed.getFacing()), head);
    }
    return parts;
  }

  private static List<BoundingBox> shape(Block block, BlockData data) {
    Location at = block.getLocation();
    return data.getCollisionShape(at).getBoundingBoxes().stream()
        .map(box -> box.clone().shift(at.toVector()))
        .toList();
  }

  static List<BoundingBox> shapes(Block block, BlockData data) {
    return parts(block, data).entrySet().stream()
        .flatMap(part -> shape(part.getKey(), part.getValue()).stream())
        .toList();
  }

  public static Obstruction obstruction(Block block, BlockData data) {
    for (var part : parts(block, data).entrySet())
      for (BoundingBox box : shape(part.getKey(), part.getValue()))
        for (Entity other : block.getWorld().getNearbyEntities(box)) {
          if (!(other instanceof LivingEntity)
              || !other.isValid()
              || other.isDead()
              || other instanceof Player p && p.getGameMode() == GameMode.SPECTATOR) continue;
          if (box.overlaps(other.getBoundingBox())) {
            Block occupied = part.getKey();
            return new Obstruction(
                other.getUniqueId(),
                other.getType().name(),
                new Pos(occupied.getX(), occupied.getY(), occupied.getZ()));
          }
        }
    return null;
  }

  static boolean fits(Villager actor, Pos feet, List<BoundingBox> shapes) {
    Location from = actor.getLocation();
    BoundingBox body =
        actor
            .getBoundingBox()
            .clone()
            .shift(feet.x() + .5 - from.getX(), feet.y() - from.getY(), feet.z() + .5 - from.getZ())
            // Navigation accepts entry into a cell before the actor reaches its center. Choose
            // enough horizontal clearance that this arrival tolerance cannot leave us in the block.
            .expand(.5, 0, .5);
    return shapes.stream().noneMatch(box -> box.overlaps(body));
  }
}
