package dev.civilizations.world;

import dev.civilizations.core.*;
import java.util.*;

/** Pure planning against immutable snapshots. Every action is revalidated live. */
public final class Planner {
  public record Result(
      List<Job> wall,
      List<Job> mine,
      List<Job> house,
      List<Job> lights,
      List<Pos> beds,
      Pos chest,
      List<Pos> stone,
      List<Pos> logs,
      List<Pos> coal,
      List<Pos> seeds,
      List<Pos> sand,
      List<Pos> redSand) {}

  public Result plan(Terrain t, Pos center, int radius, int wallHeight, int depth, int length) {
    return plan(t, center, radius, wallHeight, depth, length, p -> true);
  }

  public Result plan(
      Terrain t,
      Pos center,
      int radius,
      int wallHeight,
      int depth,
      int length,
      java.util.function.Predicate<Pos> chestAllowed) {
    return plan(t, center, radius, wallHeight, depth, length, chestAllowed, true);
  }

  public Result plan(
      Terrain t,
      Pos center,
      int radius,
      int wallHeight,
      int depth,
      int length,
      java.util.function.Predicate<Pos> chestAllowed,
      boolean templates) {
    List<Pos> beds = new ArrayList<>(),
        stone = new ArrayList<>(),
        logs = new ArrayList<>(),
        coal = new ArrayList<>(),
        seeds = new ArrayList<>(),
        sand = new ArrayList<>(),
        redSand = new ArrayList<>();
    Pos chest = null;
    for (int x = center.x() - 30; x <= center.x() + 30; x++)
      for (int z = center.z() - 30; z <= center.z() + 30; z++) {
        if (!t.available(x, z)) continue;
        int h = t.height(x, z);
        for (int y = h + 1; y >= Math.min(h - 8, center.y() - depth - 4); y--) {
          Pos p = new Pos(x, y, z);
          String m = t.type(p);
          if (m.endsWith("_BED") && t.bedFoot(p) && p.horizontal2(center) < 30 * 30) beds.add(p);
          if (m.equals("CHEST")
              && chestAllowed.test(p)
              && p.horizontal2(center) < 32 * 32
              && (chest == null || p.distance2(center) < chest.distance2(center))) chest = p;
          if (m.equals("STONE") && exposed(t, p)) stone.add(p);
          if (Set.of("COAL_ORE", "DEEPSLATE_COAL_ORE").contains(m) && exposed(t, p)) coal.add(p);
          if (m.endsWith("_LOG") && !m.startsWith("STRIPPED_") && tree(t, p)) logs.add(p);
          if (Set.of("SHORT_GRASS", "TALL_GRASS").contains(m)) seeds.add(p);
          if ((m.equals("SAND") || m.equals("RED_SAND"))
              && (t.clear(p.add(0, 1, 0)) || exposed(t, p)))
            (m.equals("SAND") ? sand : redSand).add(p);
        }
      }
    Comparator<Pos> closest = Comparator.comparingLong(p -> p.distance2(center));
    stone.sort(closest);
    coal.sort(closest);
    logs.sort(closest);
    seeds.sort(closest);
    stone = stone.stream().limit(256).toList();
    coal = coal.stream().limit(128).toList();
    logs = logs.stream().limit(128).toList();
    seeds = seeds.stream().limit(128).toList();
    if (chest == null) chest = chestSite(t, center, chestAllowed);
    return new Result(
        templates ? wall(t, center, radius, wallHeight) : List.of(),
        templates ? mine(t, center, radius, depth, length) : List.of(),
        templates ? house(t, center, radius) : List.of(),
        templates ? lights(t, center, radius) : List.of(),
        beds,
        chest,
        stone,
        logs,
        coal,
        seeds,
        List.copyOf(sand),
        List.copyOf(redSand));
  }

  public List<Job> wall(Terrain t, Pos c, int r, int height) {
    List<Pos> ring = new ArrayList<>();
    for (int x = -r; x <= r; x++) {
      ring.add(c.add(x, 0, -r));
      ring.add(c.add(x, 0, r));
    }
    for (int z = -r + 1; z < r; z++) {
      ring.add(c.add(-r, 0, z));
      ring.add(c.add(r, 0, z));
    }
    List<Job> jobs = new ArrayList<>();
    String project = "wall-" + r;
    for (Pos col : ring) {
      if (!t.available(col.x(), col.z())) return List.of();
      int h = t.groundHeight(col.x(), col.z());
      Pos ground = new Pos(col.x(), h, col.z());
      if (!t.natural(ground) || !t.dry(ground.add(0, 1, 0))) return List.of();
      int dx = Integer.compare(c.x(), col.x()), dz = Integer.compare(c.z(), col.z());
      Pos inside = new Pos(col.x() + dx, h + 1, col.z() + dz);
      if (Math.abs(t.groundHeight(inside.x(), inside.z()) - h) > 1) return List.of();
      inside = new Pos(inside.x(), t.groundHeight(inside.x(), inside.z()) + 1, inside.z());
      boolean gate = col.x() == c.x() && col.z() == c.z() - r;
      for (int y = 1; y <= height; y++) {
        Pos target = ground.add(0, y, 0);
        if (!t.clear(target)) return List.of();
        if (gate) {
          if (y == 1)
            jobs.add(
                place(
                    project,
                    target,
                    inside,
                    "OAK_FENCE_GATE",
                    "minecraft:oak_fence_gate[facing=north,open=false,in_wall=true]"));
        } else jobs.add(place(project, target, inside, "COBBLESTONE", null));
      }
    }
    jobs.sort(Comparator.comparingInt(j -> j.target.y()));
    return jobs;
  }

  public List<Job> mine(Terrain t, Pos c, int radius, int depth, int length) {
    for (int[] direction : new int[][] {{0, -1}, {1, 0}, {-1, 0}, {0, 1}}) {
      int dx = direction[0], dz = direction[1];
      Pos start = c.add(dx * (radius + 4), 0, dz * (radius + 4));
      if (!t.available(start.x(), start.z())) continue;
      int surface = t.groundHeight(start.x(), start.z());
      List<Job> jobs = new ArrayList<>();
      boolean valid = true;
      Pos previous = new Pos(start.x() - dx, surface + 1, start.z() - dz);
      for (int i = 0; i < depth + length; i++) {
        int floor = surface - Math.min(i + 1, depth);
        Pos foot = new Pos(start.x() + dx * i, floor + 1, start.z() + dz * i);
        // Require a continuous natural floor and safe fluid-free tunnel envelope.
        if (!t.natural(foot.add(0, -1, 0))) {
          valid = false;
          break;
        }
        for (int y = 2; y >= 0; y--) {
          Pos p = foot.add(0, y, 0);
          String type = t.type(p);
          if (!t.dry(p) || (!t.clear(p) && !t.natural(p))) {
            valid = false;
            break;
          }
          if (t.natural(p)) jobs.add(new Job(Job.Kind.MINE, "mine", p, previous, "", type, null));
        }
        // Widen the terminal gallery, keeping periodic side pillars.
        if (valid && i >= depth)
          for (int side = -4; side <= 4; side++) {
            if (side == 0 || Math.abs(side) == 3 && i % 4 == 0) continue;
            Pos column = foot.add(-dz * side, 0, dx * side);
            if (!t.natural(column.add(0, -1, 0))) {
              valid = false;
              break;
            }
            for (int y = 2; y >= 0; y--) {
              Pos p = column.add(0, y, 0);
              String type = t.type(p);
              if (!t.dry(p) || (!t.clear(p) && !t.natural(p))) {
                valid = false;
                break;
              }
              if (t.natural(p)) jobs.add(new Job(Job.Kind.MINE, "mine", p, foot, "", type, null));
            }
          }
        if (!valid) break;
        previous = foot;
      }
      if (valid && !jobs.isEmpty()) return jobs;
    }
    return List.of();
  }

  public List<Job> house(Terrain t, Pos c, int radius) {
    for (int[] offset : new int[][] {{4, 3}, {-8, 3}, {4, -8}, {-8, -8}}) {
      int x0 = c.x() + offset[0], z0 = c.z() + offset[1];
      if (Math.abs(offset[0]) + 5 >= radius || Math.abs(offset[1]) + 5 >= radius) continue;
      int y = t.groundHeight(x0, z0);
      boolean valid = true;
      for (int x = -1; x <= 5; x++)
        for (int z = -1; z <= 5; z++) {
          if (t.groundHeight(x0 + x, z0 + z) != y || !t.natural(new Pos(x0 + x, y, z0 + z)))
            valid = false;
          for (int h = 1; h <= 5; h++) if (!t.clear(new Pos(x0 + x, y + h, z0 + z))) valid = false;
        }
      if (!valid) continue;
      List<Job> jobs = new ArrayList<>();
      Pos entry = new Pos(x0 + 2, y + 1, z0 - 1);
      // Floor at surface+1, with a single step into the house.
      for (int x = 0; x < 5; x++)
        for (int z = 0; z < 5; z++)
          jobs.add(
              place(
                  "house",
                  new Pos(x0 + x, y + 1, z0 + z),
                  new Pos(x0 + x, y + 2, z0 + z - 1),
                  "OAK_PLANKS",
                  null));
      for (int h = 2; h <= 4; h++)
        for (int x = 0; x < 5; x++)
          for (int z = 0; z < 5; z++)
            if (x == 0 || x == 4 || z == 0 || z == 4) {
              if (x == 2 && z == 0 && h < 4) continue;
              Pos target = new Pos(x0 + x, y + h, z0 + z),
                  stand = new Pos(x0 + Math.clamp(x, 1, 3), y + 2, z0 + Math.clamp(z, 1, 3));
              jobs.add(place("house", target, stand, "OAK_PLANKS", null));
            }
      for (int x = 0; x < 5; x++)
        for (int z = 0; z < 5; z++)
          jobs.add(
              place(
                  "house",
                  new Pos(x0 + x, y + 5, z0 + z),
                  new Pos(x0 + Math.clamp(x, 1, 3), y + 2, z0 + Math.clamp(z, 1, 3)),
                  "OAK_PLANKS",
                  null));
      jobs.add(
          place(
              "house",
              new Pos(x0 + 2, y + 2, z0),
              new Pos(x0 + 2, y + 2, z0 + 1),
              "OAK_FENCE_GATE",
              "minecraft:oak_fence_gate[facing=north,open=false]"));
      // Beds are separate jobs; workers craft them from locally sheared wool and logs.
      for (int x : new int[] {1, 3})
        jobs.add(
            place(
                "house",
                new Pos(x0 + x, y + 2, z0 + 2),
                new Pos(x0 + 2, y + 2, z0 + 2),
                "WHITE_BED",
                "minecraft:white_bed[facing=south,part=foot]"));
      return jobs;
    }
    return List.of();
  }

  public List<Job> lights(Terrain t, Pos c, int radius) {
    List<Job> jobs = new ArrayList<>();
    for (int x = -radius + 3; x < radius; x += 6)
      for (int z = -radius + 3; z < radius; z += 6) {
        Pos p = new Pos(c.x() + x, t.groundHeight(c.x() + x, c.z() + z) + 1, c.z() + z);
        if (t.natural(p.add(0, -1, 0)) && t.clear(p) && t.dry(p))
          jobs.add(place("lights", p, p.add(1, 0, 0), "TORCH", null));
      }
    return jobs;
  }

  public Pos chestSite(Terrain t, Pos c) {
    return chestSite(t, c, p -> true);
  }

  public Pos chestSite(Terrain t, Pos c, java.util.function.Predicate<Pos> allowed) {
    for (int r = 1; r <= 16; r++)
      for (int dx = -r; dx <= r; dx++)
        for (int dz = -r; dz <= r; dz++) {
          if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
          Pos p = new Pos(c.x() + dx, t.groundHeight(c.x() + dx, c.z() + dz) + 1, c.z() + dz);
          if (allowed.test(p)
              && t.natural(p.add(0, -1, 0))
              && t.clear(p)
              && t.clear(p.add(0, 1, 0))
              && t.dry(p)) return p;
        }
    return null;
  }

  private Job place(String project, Pos target, Pos stand, String material, String data) {
    return new Job(Job.Kind.PLACE, project, target, stand, material, "AIR", data);
  }

  private boolean exposed(Terrain t, Pos p) {
    for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
      if (t.clear(p.add(d[0], 0, d[1]))
          && t.clear(p.add(d[0], 1, d[1]))
          && t.natural(p.add(d[0], -1, d[1]))) return true;
    return false;
  }

  private boolean tree(Terrain t, Pos p) {
    if (!Set.of("GRASS_BLOCK", "DIRT", "PODZOL", "ROOTED_DIRT").contains(t.type(p.add(0, -1, 0))))
      return false;
    for (int h = 2; h <= 8; h++)
      for (int x = -2; x <= 2; x++)
        for (int z = -2; z <= 2; z++) if (t.type(p.add(x, h, z)).endsWith("_LEAVES")) return true;
    return false;
  }
}
