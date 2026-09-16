package dev.civilizations.world;

import dev.civilizations.core.Job;
import dev.civilizations.core.Pos;
import java.util.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;

/** Construction intent excludes transient gate/redstone activity but includes structural state. */
public final class WorkState {
  private WorkState() {}

  public static boolean propertiesMatch(String expected, String actual) {
    var wanted = properties(expected);
    var found = properties(actual);
    return wanted.entrySet().stream()
        .filter(e -> !Set.of("open", "powered", "occupied").contains(e.getKey()))
        .allMatch(e -> e.getValue().equals(found.get(e.getKey())));
  }

  private static Map<String, String> properties(String data) {
    if (data == null || !data.contains("[")) return Map.of();
    Map<String, String> result = new HashMap<>();
    for (String part : data.substring(data.indexOf('[') + 1, data.lastIndexOf(']')).split(",")) {
      String[] pair = part.split("=", 2);
      if (pair.length == 2) result.put(pair[0], pair[1]);
    }
    return result;
  }

  public static boolean reusable(Job job, Block block) {
    return job.kind == Job.Kind.PLACE
        && block.getType().name().equals(job.material)
        && (!(block.getBlockData() instanceof Bed bed) || bed.getPart() == Bed.Part.FOOT);
  }

  /** Immutable surveys may omit a cell or its state; absent observation is not damage. */
  public static boolean damaged(Job job, Terrain terrain) {
    String actual = terrain.type(job.target);
    if (actual.equals("UNKNOWN")) return false;
    if (!actual.equals(job.material)) return true;
    String state = terrain.blockData(job.target);
    if (state != null && !propertiesMatch(job.blockData, state)) return true;
    if (!job.material.endsWith("_BED") || state == null) return false;
    Map<String, String> foot = properties(state);
    if (foot.containsKey("part") && !foot.get("part").equals("foot")) return true;
    String facing = foot.getOrDefault("facing", "");
    Pos head =
        switch (facing) {
          case "north" -> job.target.add(0, 0, -1);
          case "south" -> job.target.add(0, 0, 1);
          case "east" -> job.target.add(1, 0, 0);
          case "west" -> job.target.add(-1, 0, 0);
          default -> null;
        };
    if (head == null || terrain.type(head).equals("UNKNOWN")) return false;
    if (!terrain.type(head).equals(job.material)) return true;
    String headState = terrain.blockData(head);
    if (headState == null) return false;
    var observed = properties(headState);
    return !"head".equals(observed.get("part")) || !facing.equals(observed.get("facing"));
  }

  public static boolean satisfied(Job job, Block block) {
    if (job.kind == Job.Kind.MINE || job.kind == Job.Kind.CLEAR) return block.getType().isAir();
    if (job.kind != Job.Kind.PLACE
        || !reusable(job, block)
        || !propertiesMatch(job.blockData, block.getBlockData().getAsString())) return false;
    if (block.getBlockData() instanceof Bed foot) {
      Block head = block.getRelative(foot.getFacing());
      return head.getType() == block.getType()
          && head.getBlockData() instanceof Bed b
          && b.getPart() == Bed.Part.HEAD
          && b.getFacing() == foot.getFacing();
    }
    return true;
  }

  public static String snapshot(Job job, Block block) {
    String result = block.getBlockData().getAsString();
    if (job.kind == Job.Kind.PLACE && PlacementSpace.data(job) instanceof Bed desired)
      result += "|head=" + block.getRelative(desired.getFacing()).getBlockData().getAsString();
    return result;
  }
}
