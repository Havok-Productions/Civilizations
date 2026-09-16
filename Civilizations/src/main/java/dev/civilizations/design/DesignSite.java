package dev.civilizations.design;

import dev.civilizations.core.*;
import dev.civilizations.world.*;
import java.util.*;
import java.util.function.Predicate;

/** Exact, pure validation and job compilation; never reads a live world. */
final class DesignSite {
  final Terrain terrain;
  final Pos center;
  final String project;
  final Predicate<Pos> occupied;
  final List<Job> jobs = new ArrayList<>();
  final Set<Pos> reserved = new HashSet<>();
  final Set<Pos> construction = new HashSet<>();
  final Map<Pos, String> placed = new HashMap<>();
  final Map<Pos, String> prepared = new HashMap<>();
  List<String> trialWarnings;

  DesignSite(Terrain terrain, Pos center, String project, Predicate<Pos> occupied) {
    this.terrain = terrain;
    this.center = center;
    this.project = project;
    this.occupied = occupied;
  }

  void require(boolean yes, String error) {
    if (!yes) throw new IllegalArgumentException(error);
  }

  Pos ground(int x, int z) {

    int wx = Math.addExact(center.x(), x), wz = Math.addExact(center.z(), z);
    require(
        terrain.available(wx, wz),
        "Terrain not observed at world "
            + wx
            + ","
            + wz
            + " (offset "
            + x
            + ","
            + z
            + "); consult snapshot coverage and retry");
    Pos ground = new Pos(wx, terrain.groundHeight(wx, wz), wz);
    while (clear(ground) || type(ground).endsWith("_LOG") || type(ground).endsWith("_LEAVES")) {
      ground = ground.add(0, -1, 0);
      require(
          !type(ground).equals("UNKNOWN"),
          "Ground beneath vegetation is unobserved at " + ground.key());
    }
    return ground;
  }

  String type(Pos p) {
    return placed.getOrDefault(p, terrain.type(p));
  }

  boolean clear(Pos p) {
    return placed.containsKey(p)
        ? Set.of("AIR", "TORCH").contains(placed.get(p))
        : terrain.clear(p);
  }

  boolean solid(Pos p) {
    return placed.containsKey(p)
        ? Set.of("COBBLESTONE", "OAK_PLANKS", "DIRT_PATH").contains(placed.get(p))
        : terrain.natural(p) || Set.of("FARMLAND", "DIRT_PATH").contains(terrain.type(p));
  }

  void reserve(Pos p) {

    require(!terrain.type(p).equals("UNKNOWN"), "Unknown block at " + p.key());
    require(
        !occupied.test(p), "Existing structure, reserved access, or player block at " + p.key());
    reserved.add(p);
    construction.add(p);
  }

  void reserveAccess(Pos p) {
    require(!terrain.type(p).equals("UNKNOWN"), "Unknown working space at " + p.key());
    reserved.add(p);
  }

  void open(Pos p) {
    reserve(p);
    require(clear(p), "Obstructed space at " + p.key());
  }

  void openAccess(Pos p) {
    reserveAccess(p);
    require(clear(p), "Obstructed walking space at " + p.key());
  }

  Pos standNear(Pos target) {
    for (int dy : new int[] {0, -1, 1, -2, -3})
      for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
        Pos p = target.add(d[0], dy, d[1]);
        if (clear(p) && clear(p.add(0, 1, 0)) && solid(p.add(0, -1, 0)) && terrain.dry(p)) return p;
      }
    throw new IllegalArgumentException("No supported working position near " + target.key());
  }

  void add(Job.Kind kind, Pos target, Pos stand, String material, String data, int phase) {
    require(
        jobs.size() < 512,
        "Compilation work budget exhausted; split this proposal into independently buildable"
            + " stages");
    require(
        target.distance2(stand) <= 21 && Math.abs(target.y() - stand.y()) <= 3,
        "Block is beyond ordinary villager reach");
    reserve(target);
    reserveAccess(stand);
    reserveAccess(stand.add(0, 1, 0));
    reserveAccess(stand.add(0, -1, 0));
    require(
        clear(stand) && clear(stand.add(0, 1, 0)) && solid(stand.add(0, -1, 0)),
        "Unusable work position " + stand.key());
    require(
        !placed.containsKey(target) || (kind == Job.Kind.PLACE && "AIR".equals(placed.get(target))),
        "Two actions occupy " + target.key());
    if (kind == Job.Kind.PLACE) {
      require(clear(target), "Build space is occupied at " + target.key());
      require(terrain.dry(target), "Water or unavailable surroundings at " + target.key());
      // House roofs may span between already built walls; all other blocks require support.
      if (!material.equals("OAK_PLANKS"))
        require(solid(target.add(0, -1, 0)), "Missing support at " + target.key());
    }
    Job j = new Job(kind, project, target, stand, material, terrain.type(target), data);
    j.phase = phase;
    jobs.add(j);
    if (kind == Job.Kind.CLEAR || kind == Job.Kind.MINE) prepared.put(target, "AIR");
    placed.put(
        target,
        (kind == Job.Kind.MINE || kind == Job.Kind.CLEAR)
            ? "AIR"
            : kind == Job.Kind.PATH ? "DIRT_PATH" : material);
  }
}
