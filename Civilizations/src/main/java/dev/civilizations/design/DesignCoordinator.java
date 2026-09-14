package dev.civilizations.design;

import com.google.gson.Gson;
import dev.civilizations.ai.*;
import dev.civilizations.core.*;
import dev.civilizations.world.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.World;

/**
 * One shared local model; bounded village design requests and fresh validation before admission.
 */
public final class DesignCoordinator implements AutoCloseable {
  private final InferenceQueue inference;
  private final VillageConnections connections;

  private final RegionSnapshots snapshots;
  private final Executor executor;
  private final Supplier<Collection<Settlement>> settlements;
  private final Consumer<String> log;
  private final Path directory;
  private final long interval;
  private final int activeLimit;
  private final Set<String> pending = ConcurrentHashMap.newKeySet();
  private final Map<String, Long> next = new ConcurrentHashMap<>();
  private final Map<String, String> statuses = new ConcurrentHashMap<>();
  private volatile boolean closed;
  private java.util.function.BiConsumer<String, Map<String, ?>> observer = (v, e) -> {};

  public void observe(java.util.function.BiConsumer<String, Map<String, ?>> observer) {
    this.observer = observer;
  }

  public DesignCoordinator(
      InferenceQueue inference,
      VillageConnections connections,
      RegionSnapshots snapshots,
      Executor executor,
      Supplier<Collection<Settlement>> settlements,
      Path directory,
      long interval,
      int activeLimit,
      Consumer<String> log) {
    this.inference = inference;
    this.connections = connections;
    this.snapshots = snapshots;
    this.executor = executor;
    this.settlements = settlements;
    this.directory = directory;
    this.interval = interval;
    this.activeLimit = activeLimit;
    this.log = log;
  }

  public String status(Settlement v) {
    return statuses.getOrDefault(v.id(), "waiting for an eligible village need");
  }

  private Predicate<Pos> occupied(Settlement v) {
    Set<Pos> positions = new HashSet<>();
    Set<String> playerBlocks = new HashSet<>();
    for (Settlement other : settlements.get())
      if (other.world().equals(v.world())) {
        positions.addAll(other.layoutOccupancy());
        playerBlocks.addAll(other.snapshot().playerBlocks);
      }
    return p -> positions.contains(p) || playerBlocks.contains(p.key());
  }

  public void consider(
      Settlement v, World world, Terrain terrain, Map<String, Integer> resourceSites) {
    try {
      considerReady(v, world, terrain, resourceSites);
    } catch (RuntimeException e) {
      pending.remove(v.id());
      next.put(v.id(), System.currentTimeMillis() + interval);
      feedback(v, "Could not prepare design observations: " + e.getMessage());
    }
  }

  private void considerReady(
      Settlement v, World world, Terrain terrain, Map<String, Integer> resourceSites) {
    long now = System.currentTimeMillis();
    Set<String> kinds = DesignNeeds.allowed(v, activeLimit);
    if (closed || now < next.getOrDefault(v.id(), 0L) || kinds.isEmpty() || !pending.add(v.id()))
      return;
    Map<String, Object> report = new LinkedHashMap<>();
    Map<String, Object> map = TerrainMap.capture(terrain, v, occupied(v));
    report.put("terrain", map);
    report.put("allowed_kinds", kinds);
    report.put("village_needs", v.needs().report(v, Map.of(), now));
    report.put("beds", v.beds().stream().map(p -> relative(v, p)).toList());
    report.put("chest", v.chest() == null ? null : relative(v, v.chest()));
    report.put("community_chests", v.chests().stream().map(p -> relative(v, p)).toList());
    report.put("stock", v.stock());
    report.put("local_resource_sites", resourceSites);
    report.put("stock_age_seconds", Math.min(9999, v.stockAge(now) / 1000));
    report.put("feedback", v.designFeedback());
    report.put("supply_requests", v.supplyNeeds(now));
    report.put("shared_facts", v.knowledge().report(now));
    report.put("crafting_table", v.craftingTable() == null ? null : relative(v, v.craftingTable()));
    List<Map<String, Object>> examples =
        SiteObservations.candidates(terrain, v, occupied(v), kinds);
    report.put("validated_site_examples", examples);
    if (examples.isEmpty()) {
      next.put(v.id(), now + interval);
      pending.remove(v.id());
      statuses.put(
          v.id(), "No validated construction site in the loaded survey; rescan before thinking");
      return;
    }

    String schema =
        kinds.contains("mine")
                && resourceSites.getOrDefault("COAL", 0) == 0
                && v.supplyNeeds(now).stream().anyMatch(s -> s.material().equals("COAL"))
            ? SupplyDesignSchema.coalRoutes(examples)
            : Blueprint.SCHEMA;
    if (!schema.equals(Blueprint.SCHEMA)) {
      report.put("allowed_kinds", Set.of("mine"));
      report.put(
          "supply_route_instruction",
          "Urgent fuel request: choose one surveyed coal-bearing route. Geometry is fixed by the"
              + " response schema; explain why it meets the need.");
    }
    if (schema.equals(Blueprint.SCHEMA) && !examples.isEmpty()) {
      schema = SupplyDesignSchema.surveyed(examples);
      report.put(
          "allowed_kinds",
          examples.stream()
              .map(e -> ((Blueprint) e.get("blueprint")).kind())
              .collect(java.util.stream.Collectors.toSet()));
      report.put(
          "site_instruction",
          "Choose a validated_site_examples blueprint. The response schema keeps its surveyed"
              + " geometry; explain which need it meets. Do not invent unsurveyed coordinates.");
    }
    report.put(
        "goal_dependencies",
        v.jobs().stream()
            .filter(j -> !j.complete)
            .collect(
                java.util.stream.Collectors.toMap(
                    j -> j.project, j -> j, (a, b) -> a, LinkedHashMap::new))
            .values()
            .stream()
            .limit(4)
            .map(j -> TaskPlan.describe(j, Map.of()))
            .toList());
    report.put(
        "projects",
        v.jobs().stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    j -> j.project, java.util.stream.Collectors.counting())));
    report.put(
        "previous_designs",
        v.designs().stream()
            .skip(Math.max(0, v.designs().size() - 6))
            .map(
                d ->
                    Map.of(
                        "blueprint",
                        d.blueprint(),
                        "origin",
                        d.origin() == null ? v.center() : d.origin(),
                        "completed",
                        v.allComplete(d.project())))
            .toList());
    report.put(
        "worker_results",
        v.members().stream()
            .limit(5)
            .flatMap(id -> v.memories(id).stream().skip(Math.max(0, v.memories(id).size() - 2)))
            .toList());
    write(v, "map", report);
    next.put(v.id(), now + interval);
    statuses.put(v.id(), "local AI mapping/designing");
    boolean submitted =
        inference.submit(
            "design:" + v.id(),
            SYSTEM,
            new Gson().toJson(report),
            ReasoningMode.DESIGN,
            schema,
            now + 180_000,
            Blueprint::parse,
            blueprint -> {
              if (closed) {
                pending.remove(v.id());
                return;
              }
              if (blueprint == null) {
                next.put(v.id(), System.currentTimeMillis() + interval);
                feedback(
                    v,
                    "Model did not return a valid blueprint; see /civ ai for the inference error");
                pending.remove(v.id());
                return;
              }
              if (blueprint.kind().equals("wait")) {
                feedback(v, "Waiting: " + blueprint.purpose());
                pending.remove(v.id());
                return;
              }
              snapshots
                  .capture(world, v.center(), 32)
                  .thenAcceptAsync(fresh -> accept(v, blueprint, fresh), executor)
                  .whenComplete(
                      (unused, error) -> {
                        if (error != null && !closed)
                          feedback(v, "Design validation failed: " + error.getMessage());
                        pending.remove(v.id());
                      });
            });
    if (!submitted) {
      pending.remove(v.id());
      next.put(v.id(), now + 30_000);
      statuses.put(v.id(), "waiting for local model/queue");
    }
  }

  private synchronized void accept(Settlement v, Blueprint blueprint, Terrain terrain) {
    connections.read(() -> acceptCurrent(v, blueprint, terrain));
  }

  private void acceptCurrent(Settlement v, Blueprint blueprint, Terrain terrain) {
    if (closed || v.paused()) return;
    try {
      if (!DesignNeeds.allowed(v, activeLimit).contains(blueprint.kind()))
        throw new IllegalArgumentException("Village need changed or this kind is already planned");
      String project =
          "design-" + blueprint.kind() + "-" + UUID.randomUUID().toString().substring(0, 8);
      List<Pos> landmarks = new ArrayList<>(v.beds());
      landmarks.addAll(v.chests());
      if (blueprint.kind().equals("wall"))
        v.jobs().stream()
            .filter(j -> j.project.startsWith("wall-") || j.project.startsWith("design-wall-"))
            .map(j -> j.target)
            .distinct()
            .forEach(landmarks::add);
      DesignCompiler.Result compiled =
          new DesignCompiler()
              .compile(blueprint, terrain, v.center(), project, occupied(v), landmarks);
      if (blueprint.kind().equals("house")) {
        Optional<DesignRecord> defense =
            v.designs().stream().filter(d -> d.kind().equals("wall")).reduce((a, b) -> b);
        if (defense.isPresent()) {
          Blueprint wall = Blueprint.parse(defense.get().blueprint());
          if (compiled.jobs().stream()
              .anyMatch(
                  j ->
                      !DesignCompiler.insideWall(
                          wall,
                          defense.get().origin() == null ? v.center() : defense.get().origin(),
                          j.target)))
            throw new IllegalArgumentException(
                "House must fit inside the planned defenses; expand the wall first if more space is"
                    + " needed");
        }
      }
      String json = new Gson().toJson(blueprint);
      DesignRecord record =
          new DesignRecord(
              project,
              blueprint.kind(),
              blueprint.purpose(),
              json,
              compiled.materials(),
              compiled.jobs().size(),
              System.currentTimeMillis());
      if (!v.addDesign(
          record,
          compiled.jobs(),
          compiled.reservations(),
          activeLimit + (blueprint.kind().equals("mine") ? 1 : 0)))
        throw new IllegalArgumentException("Space or project capacity changed before admission");
      write(
          v,
          "plan",
          Map.of("design", record, "jobs", compiled.jobs(), "reserved", compiled.reservations()));
      feedback(
          v,
          "Accepted "
              + project
              + ": "
              + record.purpose()
              + "; "
              + record.jobs()
              + " actions; materials "
              + record.materials());
    } catch (IllegalArgumentException e) {
      next.put(v.id(), System.currentTimeMillis() + interval);
      feedback(
          v,
          "Rejected "
              + blueprint.kind()
              + " at "
              + blueprint.x()
              + ","
              + blueprint.z()
              + ": "
              + e.getMessage()
              + ". Revise the coordinates, dimensions, or route.");
    }
  }

  private Map<String, Integer> relative(Settlement v, Pos p) {
    return Map.of(
        "x", p.x() - v.center().x(), "y", p.y() - v.center().y(), "z", p.z() - v.center().z());
  }

  private void feedback(Settlement v, String message) {
    observer.accept(v.id(), Map.of("stage", "validation", "message", message));
    v.designFeedback(message);
    statuses.put(v.id(), message);
    log.accept("Village design " + v.id() + ": " + message);
  }

  private void write(Settlement v, String name, Object value) {
    observer.accept(v.id(), Map.of("stage", name, "details", value));
    try {
      Files.createDirectories(directory);
      String id = UUID.fromString(v.id()).toString();
      Path target = directory.resolve(id + "-" + name + ".json"),
          temp = directory.resolve(id + "-" + name + ".pending");
      Files.writeString(temp, new Gson().toJson(value));
      try {
        Files.move(
            temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (Exception e) {
      log.accept("Could not save design review file: " + e.getMessage());
    }
  }

  @Override
  public void close() {
    closed = true;
    pending.clear();
  }

  static final String SYSTEM =
      """
      You are the village architect. Design ONE useful project from allowed_kinds, based on the observed terrain map, beds/chest, resources, previous designs and failures. Return a compact JSON blueprint matching the schema, never executable code or commands. All x/z are integer offsets from the village center; +/-24 bounds include the entire footprint. Map cells cover 3x3 blocks and heights are offsets from the center's y. Unknown/occupied/wet cells must be avoided; exact validation will reject unsafe geometry. Respond wait if no feasible needed project exists. Prioritize missing shelter/food, mob defenses, then required materials and access. Do not repeat a rejected location unchanged. Reuse successful design dimensions where appropriate, adapting their position to current terrain. Existing projects keep their materials and are not deleted by a new design.
      Resolve supply_requests before adding another project that needs the same unavailable materials. Missing coal calls for a safe mine when no reachable exposed source is available, not endless requests to gather. Workers obtain wood and craft a real table, sticks and wooden pickaxe before excavation; stone tools are required for iron/copper. validated_site_examples were checked against this snapshot: select/adapt one or design a different safe layout. Prefer a mine with observed coal in its route when coal is needed. Zero coal means exploration only, not a promise of coal. goal_dependencies and shared_facts describe actual prerequisites and failures. Keep reasoning brief and return a final blueprint.
      kind house: x/z is the minimum corner, width/depth 5..9, height 3..4, direction is the centered entrance side. Requires dry natural ground, a one-block clear margin, roof space. One layer of unprotected dirt/grass can be graded by real clearance jobs before construction; buildings, trees and deeper excavation cannot be cleared by house preparation. points optionally contains up to four bed FOOT offsets inside this footprint; each bed faces south and uses z+1 too. Keep a walkable interior/doorway. Empty points creates one bed. Choose the size and placement yourself.
      kind wall: points are 4..16 vertices of a simple CLOSED axis-aligned polygon (do not repeat the first vertex), enclosing center and known beds/chest. Route may bend around obstacles. height=3. x/z selects one gate on a STRAIGHT section; direction north/south for a horizontal wall, east/west for a vertical wall. Requires clear dry natural ground, normal accessible working positions and an accessible gate on both sides. At most 512 resulting block actions.
      kind path: points are 2..16 axis-aligned polyline vertices; width=1. Connect useful entrances/storage/farms by shaping existing dirt/grass; avoid existing reserved structure footprints. No water bridges or excavation. Slope must be walkable.
      kind farm: x/z minimum corner, width/depth 1..5, product at most 16. Use dirt/farmland within four blocks of EXISTING water, with adjacent walking space. Prefer narrow strips so crops remain accessible. Real seeds and normal wheat growth are required.
      kind lights: points are 1..16 distinct ground torch sites near paths/homes, spaced about six blocks apart. Needs actual coal and sticks/wood. Avoid already planned lights.
      kind mine: x/z entrance at least ten blocks from center, direction is travel direction, depth 3..12 descending steps, width 0..8 is subsequent level gallery length. A three-block-high corridor is generated in order. Entire route must fit the map and stay in dry natural terrain, away from buildings, water, protected land and other designs. Choose another entrance/direction if rejected. It never excavates under a surface building.
      Unused numeric fields must be 0; unused points must be []; direction defaults north. purpose is a short sentence explaining the observed need. No imaginary resource generation, instant building, teleportation or growth acceleration. Proposals are not proof that construction succeeded. Villagers perform validated jobs at normal speed using real ingredients.
      """;
}
