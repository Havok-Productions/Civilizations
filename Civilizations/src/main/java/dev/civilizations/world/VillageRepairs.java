package dev.civilizations.world;

import dev.civilizations.core.*;
import java.util.*;

/**
 * Learns existing building blocks near beds and restores observed blocks only after they disappear.
 */
public final class VillageRepairs {
  private VillageRepairs() {}

  private static boolean building(String type) {
    return type.endsWith("_PLANKS")
        || type.endsWith("_STAIRS")
        || type.endsWith("_SLAB")
        || Set.of(
                "COBBLESTONE", "MOSSY_COBBLESTONE", "STONE_BRICKS", "BRICKS", "GLASS", "GLASS_PANE")
            .contains(type);
  }

  private static boolean timber(Terrain t, Pos p) {
    if (!t.type(p).endsWith("_LOG")) return false;
    for (int x = -2; x <= 2; x++)
      for (int z = -2; z <= 2; z++)
        for (int y = -1; y <= 1; y++) if (t.type(p.add(x, y, z)).endsWith("_PLANKS")) return true;
    return false;
  }

  public static Map<String, Integer> survey(Settlement v, Terrain t) {
    int learned = 0, repairs = 0;
    for (Job job : v.jobs())
      if (job.complete && job.everBuilt && job.kind == Job.Kind.PLACE && t.clear(job.target))
        v.damaged(job.id);
    for (Pos bed : v.beds())
      for (int x = -7; x <= 7; x++)
        for (int z = -7; z <= 7; z++)
          for (int y = -2; y <= 7; y++) {
            Pos p = bed.add(x, y, z);
            String type = t.type(p);
            if ((building(type) || timber(t, p)) && !v.playerProtected(p) && !v.protectedPos(p))
              if (v.recordRepair(new RepairBlock(p, type, t.blockData(p)))) learned++;
          }
    for (RepairBlock known : v.repairBlocks()) {
      Pos p = known.position();
      if (!t.clear(p)
          || v.playerProtected(p)
          || v.jobs().stream().anyMatch(j -> j.target.equals(p))) continue;
      Pos stand = stand(t, p);
      if (stand == null) continue;
      Job job =
          new Job(
              Job.Kind.PLACE,
              "repair-" + p.key(),
              p,
              stand,
              known.material(),
              t.type(p),
              known.blockData());
      job.everBuilt = true;
      if (v.addProject(job.project, List.of(job))) repairs++;
    }
    return Map.of(
        "newly_recorded",
        learned,
        "recorded_blocks",
        v.repairBlocks().size(),
        "repair_jobs_added",
        repairs);
  }

  private static Pos stand(Terrain t, Pos p) {
    for (int dy : new int[] {0, -1, 1, -2, -3})
      for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
        Pos q = p.add(d[0], dy, d[1]);
        String support = t.type(q.add(0, -1, 0));
        if (t.clear(q)
            && t.clear(q.add(0, 1, 0))
            && (t.natural(q.add(0, -1, 0)) || building(support))
            && t.dry(q)) return q;
      }
    return null;
  }
}
