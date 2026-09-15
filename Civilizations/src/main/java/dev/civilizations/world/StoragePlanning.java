package dev.civilizations.world;

import dev.civilizations.core.*;
import java.util.*;

/** One shared storage project at a time, paid and placed through the ordinary worker executor. */
public final class StoragePlanning {
  private StoragePlanning() {}

  public static List<Job> plan(
      Settlement village, Terrain terrain, Pos origin, int radius, long now) {
    if (village.chest() != null && !village.storageCapacity().needsExpansion(village.chests(), now))
      return List.of();
    if (village.jobs().stream().anyMatch(j -> j.project.startsWith("storage-") && !j.complete))
      return List.of();
    Set<Pos> occupied = village.layoutOccupancy();
    for (int r = 1; r <= radius; r++)
      for (int dx = -r; dx <= r; dx++)
        for (int dz = -r; dz <= r; dz++) {
          if (Math.max(Math.abs(dx), Math.abs(dz)) != r
              || !terrain.available(origin.x() + dx, origin.z() + dz)) continue;
          Pos p =
              new Pos(
                  origin.x() + dx,
                  terrain.groundHeight(origin.x() + dx, origin.z() + dz) + 1,
                  origin.z() + dz);
          if (!terrain.clear(p)
              || !terrain.clear(p.add(0, 1, 0))
              || !terrain.natural(p.add(0, -1, 0))
              || !terrain.dry(p)
              || occupied.contains(p)
              || occupied.contains(p.add(0, 1, 0))
              || village.playerProtected(p)
              || village.chests().stream().anyMatch(c -> c.distance2(p) <= 2)) continue;
          for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Pos stand = p.add(d[0], 0, d[1]);
            if (terrain.clear(stand)
                && terrain.clear(stand.add(0, 1, 0))
                && terrain.natural(stand.add(0, -1, 0))
                && !occupied.contains(stand)
                && terrain.dry(stand)) {
              String project = "storage-" + p.key();
              return List.of(
                  new Job(Job.Kind.PLACE, project, p, stand, "CHEST", terrain.type(p), null));
            }
          }
        }
    return List.of();
  }

  public static synchronized boolean schedule(
      Settlement village, Terrain terrain, Pos origin, int radius, long now) {
    List<Job> jobs = plan(village, terrain, origin, radius, now);
    return !jobs.isEmpty() && village.addProject(jobs.getFirst().project, jobs);
  }
}
