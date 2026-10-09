package dev.civilizations.learning;

import com.google.gson.*;
import dev.civilizations.core.Pos;
import dev.civilizations.navigation.NavigationMap;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Durable complete snapshots; map-ring filenames alone are never trusted as identities. */
public final class RecoveryReplay {
  private RecoveryReplay() {}

  public static NavigationMap decode(String json) {
    return decode(JsonParser.parseString(json).getAsJsonObject());
  }

  public static void save(
      Path root, String id, SkillContext context, VoxelRecoveryPlanner.Evaluation evaluation)
      throws IOException {
    Files.createDirectories(root);
    var report =
        Map.of(
            "schema",
            1,
            "id",
            id,
            "map",
            context.map().describe(),
            "origin",
            context.origin(),
            "goal",
            context.goal(),
            "reach2",
            context.reach2(),
            "inventory",
            context.inventory(),
            "observation",
            context.observation(),
            "simulation",
            evaluation,
            "evidence_level",
            "voxel_prediction_not_physical_execution");
    try (var out = new GZIPOutputStream(Files.newOutputStream(root.resolve(id + ".json.gz")))) {
      out.write(new Gson().toJson(report).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  public static NavigationMap decode(JsonObject map) {
    Gson json = new Gson();
    Pos center = json.fromJson(map.get("center"), Pos.class);
    int radius = map.get("radius").getAsInt(), vertical = map.get("vertical").getAsInt();
    long count = (2L * radius + 1) * (2L * radius + 1) * (2L * vertical + 4);
    if (radius < 0 || vertical < 0 || count > 10_000_000)
      throw new IllegalArgumentException("Invalid snapshot bounds");
    var palette = map.getAsJsonArray("palette");
    var states = map.getAsJsonArray("block_states");
    var removal = map.getAsJsonArray("removal_reasons");
    if (palette.size() != states.size() || palette.size() != removal.size())
      throw new IllegalArgumentException("Snapshot palette mismatch");
    var values = new ArrayList<NavigationMap.Cell>();
    for (int i = 0; i < palette.size(); i++) {
      String[] label = palette.get(i).getAsString().split(":");
      if (label.length != 2) throw new IllegalArgumentException("Invalid palette entry");
      values.add(
          new NavigationMap.Cell(
              label[0],
              NavigationMap.Kind.valueOf(label[1]),
              states.get(i).getAsString(),
              removal.get(i).getAsString()));
    }
    var cells = new HashMap<Pos, NavigationMap.Cell>();
    var runs = map.getAsJsonArray("runs");
    if (runs.size() % 2 != 0) throw new IllegalArgumentException("Odd snapshot runs");
    long cursor = 0;
    int height = 2 * vertical + 4, width = 2 * radius + 1;
    for (int i = 0; i < runs.size(); i += 2) {
      int id = runs.get(i).getAsInt(), length = runs.get(i + 1).getAsInt();
      if (id < 0 || id >= values.size() || length <= 0 || cursor + length > count)
        throw new IllegalArgumentException("Invalid snapshot run");
      for (int j = 0; j < length; j++, cursor++) {
        int x = (int) (cursor / (width * height)) - radius,
            z = (int) (cursor / height % width) - radius,
            y = (int) (cursor % height) - vertical - 1;
        cells.put(center.add(x, y, z), values.get(id));
      }
    }
    if (cursor != count) throw new IllegalArgumentException("Incomplete snapshot volume");
    return new NavigationMap(center, radius, vertical, cells);
  }

  /**
   * Read-only replay of a captured case; output explicitly distinguishes prediction from execution.
   */
  public static void main(String[] args) throws Exception {
    if (args.length != 2)
      throw new IllegalArgumentException("Usage: RecoveryReplay captured-case.json report.json");
    var json = new Gson();
    var input = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
    var map = decode(input.getAsJsonObject("map"));
    Pos origin = json.fromJson(input.get("origin"), Pos.class),
        goal = json.fromJson(input.get("goal"), Pos.class);
    Map<String, Integer> inventory = new HashMap<>();
    input
        .getAsJsonObject("inventory")
        .entrySet()
        .forEach(e -> inventory.put(e.getKey(), e.getValue().getAsInt()));
    var context =
        SkillContext.create(
            map,
            origin,
            goal,
            input.get("reach2").getAsInt(),
            inventory,
            input.get("failure").getAsString());
    var result =
        Map.of(
            "source",
            args[0],
            "goal_spatial_facts",
            RecoverySpatialFacts.at(map, origin, goal),
            "origin_spatial_facts",
            RecoverySpatialFacts.at(map, origin, origin),
            "evaluation",
            VoxelRecoveryPlanner.evaluate(context),
            "physical_execution",
            false,
            "goal_inside_snapshot",
            map.contains(goal));
    Files.writeString(
        Path.of(args[1]), new GsonBuilder().setPrettyPrinting().create().toJson(result));
    System.out.println("Voxel replay saved to " + args[1] + "; no physical execution");
  }
}
