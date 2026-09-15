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

  private final TerrainSurvey snapshots;
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
      TerrainSurvey snapshots,
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
    String status = statuses.getOrDefault(v.id(), "waiting for an eligible village need");
    return v.proposals().isEmpty()
        ? status
        : status + "; retained proposals=" + v.proposals().size();
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
    long now = System.currentTimeMillis();
    if (closed || v.paused() || now < next.getOrDefault(v.id(), 0L) || !pending.add(v.id())) return;
    try {
      DesignProposal retry =
          v.proposals().stream()
              .filter(p -> p.due(now))
              .filter(p -> DesignNeeds.allowed(v, activeLimit).contains(p.kind()))
              .min(Comparator.comparingLong(DesignProposal::retryAt))
              .orElse(null);
      if (retry != null) {
        next.put(v.id(), now + interval);
        if (retry.needsSalvage()) salvageSurvey(v, world, retry, resourceSites);
        else survey(v, world, retry);
        return;
      }
      Pos focus = DesignFocus.select(v);
      snapshots
          .capture(world, focus, 32)
          .thenAcceptAsync(t -> considerReady(v, world, focus, t, resourceSites), executor)
          .exceptionally(
              error -> {
                pending.remove(v.id());
                next.put(v.id(), System.currentTimeMillis() + interval);
                feedback(v, "Design survey unavailable: " + error.getMessage());
                return null;
              });
    } catch (RuntimeException e) {
      pending.remove(v.id());
      next.put(v.id(), System.currentTimeMillis() + interval);
      feedback(v, "Could not prepare design observations: " + e.getMessage());
    }
  }

  private void considerReady(
      Settlement v, World world, Pos origin, Terrain terrain, Map<String, Integer> resourceSites) {
    considerReady(v, world, origin, terrain, resourceSites, null);
  }

  private void salvageSurvey(
      Settlement v, World world, DesignProposal proposal, Map<String, Integer> resourceSites) {
    // A failed distant/oversized survey must not prevent the model from repairing its coordinates.
    snapshots
        .capture(world, proposal.origin(), 32)
        .thenAcceptAsync(
            t -> considerReady(v, world, proposal.origin(), t, resourceSites, proposal), executor)
        .exceptionally(
            error -> {
              deferred(v, proposal, "Fresh survey unavailable: " + error.getMessage());
              pending.remove(v.id());
              return null;
            });
  }

  private void considerReady(
      Settlement v,
      World world,
      Pos origin,
      Terrain terrain,
      Map<String, Integer> resourceSites,
      DesignProposal salvage) {
    long now = System.currentTimeMillis();
    Set<String> kinds = DesignNeeds.allowed(v, activeLimit);
    if (salvage != null) kinds = kinds.contains(salvage.kind()) ? Set.of(salvage.kind()) : Set.of();
    if (closed || v.paused() || kinds.isEmpty()) {
      pending.remove(v.id());
      next.put(v.id(), now + interval);
      return;
    }
    if (salvage != null && ProposalSalvage.geometryValid(salvage)) {
      // Terrain may have changed since failure. Keep the original layout if it now works.
      DesignProposal recheck =
          salvage.status().equals("needs_revision")
              ? salvage.revised(
                  salvage.blueprint(),
                  "awaiting_validation",
                  "Rechecking the retained site after earlier salvage failed",
                  now)
              : salvage;
      var fresh = accept(v, recheck, terrain);
      if (fresh == null || fresh.accepted() || !fresh.proposal().needsSalvage()) {
        pending.remove(v.id());
        return;
      }
    }
    observer.accept(
        v.id(),
        Map.of(
            "stage", "survey_coverage", "origin", origin, "coverage", terrain.observationReport()));
    if (!terrain.available(origin.x(), origin.z())) {
      pending.remove(v.id());
      next.put(v.id(), now + 5000);
      statuses.put(v.id(), "Waiting for fresh observations at inhabited focus; retrying survey");
      return;
    }
    var viewData = v.snapshot();
    viewData.center = origin;
    Settlement view = new Settlement(viewData);
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("survey_origin", origin);
    report.put("snapshot_coverage", terrain.observationReport());
    report.put(
        "coordinate_example",
        Map.of(
            "world_position", origin.add(6, 0, -6), "blueprint_offset", Map.of("x", 6, "z", -6)));
    report.put(
        "defense_scope",
        "Local defenses may protect a bed/chest neighborhood; one wall need not enclose every"
            + " distant landmark in the merged village.");
    Map<String, Object> map = TerrainMap.capture(terrain, view, occupied(v));
    report.put("terrain", map);
    report.put("allowed_kinds", kinds);
    report.put("village_needs", v.needs().report(v, Map.of(), now));
    report.put("beds", v.beds().stream().map(p -> relative(view, p)).toList());
    report.put("chest", v.chest() == null ? null : relative(view, v.chest()));
    report.put("community_chests", v.chests().stream().map(p -> relative(view, p)).toList());
    report.put("stock", v.stock());
    report.put("local_resource_sites", resourceSites);
    report.put("material_sources", MaterialSources.knowledge());
    report.put("stock_age_seconds", Math.min(9999, v.stockAge(now) / 1000));
    report.put("feedback", v.designFeedback());
    if (salvage != null) report.put("salvage_existing_proposal", ProposalSalvage.context(salvage));
    report.put(
        "retained_proposals",
        v.proposals().stream()
            .sorted(Comparator.comparingLong(DesignProposal::retryAt))
            .limit(6)
            .map(
                p ->
                    Map.of(
                        "id",
                        p.id(),
                        "kind",
                        p.kind(),
                        "origin",
                        p.origin(),
                        "blueprint",
                        p.blueprint(),
                        "status",
                        p.status(),
                        "reason",
                        p.reason()))
            .toList());
    report.put("supply_requests", v.supplyNeeds(now));
    report.put("shared_facts", v.knowledge().report(now));
    report.put(
        "crafting_table", v.craftingTable() == null ? null : relative(view, v.craftingTable()));
    Map<String, Integer> rejected = new TreeMap<>();
    List<Map<String, Object>> examples =
        SiteObservations.candidates(terrain, view, occupied(v), kinds, rejected);
    report.put("validated_site_examples", examples);
    report.put("site_rejections", rejected);
    report.put(
        "site_instruction",
        "Examples are suggestions, not an allowed list. Propose different coordinates, dimensions,"
            + " or a local wall when useful. Exact terrain and work-position checks follow. Explain"
            + " how you address recorded site failures.");
    String schema = Blueprint.SCHEMA;
    if (examples.isEmpty())
      observer.accept(
          v.id(),
          Map.of(
              "stage",
              "site_search",
              "reason",
              "No surveyed example; model may propose another layout",
              "rejections",
              rejected));
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
    statuses.put(
        v.id(),
        salvage == null
            ? "local AI mapping/designing"
            : "salvaging retained " + salvage.kind() + " proposal " + salvage.id());
    boolean submitted =
        inference.submit(
            "design:" + v.id(),
            SYSTEM,
            new Gson().toJson(report),
            ReasoningMode.DESIGN,
            schema,
            now + 180_000,
            text -> Blueprint.parse(text, origin),
            blueprint -> {
              if (closed) {
                pending.remove(v.id());
                return;
              }
              if (salvage != null) {
                salvageResponse(v, world, salvage, blueprint, examples);
                return;
              }
              if (blueprint == null) {
                next.put(v.id(), System.currentTimeMillis() + interval);
                feedback(
                    v,
                    "Model did not return a valid blueprint; see /civ ai for the inference error");
                useObservedAlternative(v, world, origin, examples, "invalid model response");
                return;
              }
              if (blueprint.kind().equals("wait")) {
                feedback(v, "Waiting: " + blueprint.purpose());
                pending.remove(v.id());
                return;
              }
              receive(v, world, blueprint, origin);
            });
    if (!submitted) {
      next.put(v.id(), now + 30_000);
      if (salvage != null) salvageResponse(v, world, salvage, null, examples);
      else {
        pending.remove(v.id());
        statuses.put(v.id(), "waiting for local model/queue");
      }
    }
  }

  private void salvageResponse(
      Settlement v,
      World world,
      DesignProposal proposal,
      Blueprint response,
      List<Map<String, Object>> examples) {
    boolean usable = ProposalSalvage.usable(proposal, response);
    Blueprint revision = usable ? response : ProposalSalvage.alternative(proposal, examples);
    if (revision == null) {
      connections.read(
          () -> {
            Settlement current = current(v);
            if (current == null || closed) return;
            DesignProposal saved =
                current.proposals().stream()
                    .filter(p -> p.id().equals(proposal.id()))
                    .findFirst()
                    .orElse(null);
            if (saved == null) return;
            String why =
                "No usable model revision or observed alternative yet; preserve this goal. Prior"
                    + " blocker: "
                    + proposal.reason();
            // Keep the original reason stable; the failed salvage is recorded separately for
            // inspection.
            var waiting =
                saved.waiting(
                    "needs_revision", saved.reason(), System.currentTimeMillis() + 2 * interval);
            current.proposal(waiting);
            write(current, "salvage", Map.of("proposal", waiting, "result", why));
            feedback(current, why);
          });
      pending.remove(v.id());
      return;
    }
    java.util.concurrent.atomic.AtomicReference<DesignProposal> revised =
        new java.util.concurrent.atomic.AtomicReference<>();
    connections.read(
        () -> {
          Settlement current = current(v);
          if (current == null || closed) return;
          DesignProposal saved =
              current.proposals().stream()
                  .filter(p -> p.id().equals(proposal.id()))
                  .findFirst()
                  .orElse(null);
          if (saved == null)
            return; // Late model response after another callback admitted the proposal.
          write(
              current,
              "salvage-response",
              Map.of(
                  "proposal_id",
                  saved.id(),
                  "model_response",
                  response == null ? "No model output available" : response,
                  "selected_revision",
                  revision,
                  "source",
                  usable ? "model" : "validated local alternative"));
          var candidate =
              ProposalSalvage.revise(
                  saved,
                  revision,
                  usable
                      ? "Model salvage of retained proposal"
                      : "Observed local salvage of retained proposal",
                  System.currentTimeMillis() + 2 * interval);
          current.proposal(candidate);
          revised.set(candidate);
          write(current, "salvage", candidate);
          feedback(current, "Trying salvaged " + candidate.kind() + ": " + candidate.reason());
        });
    DesignProposal candidate = revised.get();
    if (candidate == null || candidate.status().equals("needs_revision")) pending.remove(v.id());
    else survey(v, world, candidate);
  }

  private void useObservedAlternative(
      Settlement v, World world, Pos origin, List<Map<String, Object>> examples, String reason) {
    Blueprint alternative =
        examples.stream()
            .map(e -> (Blueprint) e.get("blueprint"))
            .min(
                Comparator.comparingInt(
                    b -> b.kind().equals("wall") ? 0 : b.kind().equals("mine") ? 1 : 2))
            .orElse(null);
    if (alternative == null) {
      pending.remove(v.id());
      return;
    }
    observer.accept(
        v.id(),
        Map.of(
            "stage",
            "local_fallback",
            "reason",
            reason,
            "blueprint",
            alternative,
            "basis",
            "observed feasible candidate; requires fresh validation"));
    receive(v, world, alternative, origin);
  }

  /** Find the current state after an asynchronous survey/model call races with a merge. */
  private Settlement current(Settlement previous) {
    return settlements.get().stream()
        .filter(
            v ->
                !v.retired()
                    && (v.id().equals(previous.id()) || v.absorbedIds().contains(previous.id())))
        .findFirst()
        .orElse(null);
  }

  private void receive(Settlement previous, World world, Blueprint blueprint, Pos origin) {
    java.util.concurrent.atomic.AtomicReference<DesignProposal> retained =
        new java.util.concurrent.atomic.AtomicReference<>();
    connections.read(
        () -> {
          Settlement v = current(previous);
          if (v == null || closed) return;
          DesignProposal p =
              DesignProposals.retain(v, blueprint, origin, System.currentTimeMillis());
          retained.set(p);
          write(v, "proposal", p);
          if (p.status().equals("needs_revision")) feedback(v, p.reason());
        });
    DesignProposal proposal = retained.get();
    if (proposal == null || proposal.status().equals("needs_revision")) {
      pending.remove(previous.id());
      return;
    }
    survey(previous, world, proposal);
  }

  private void survey(Settlement previous, World world, DesignProposal proposal) {
    try {
      Blueprint blueprint = Blueprint.parse(proposal.blueprint());
      snapshots
          .capture(world, proposal.origin(), blueprint.surveyRadius())
          .thenAcceptAsync(terrain -> accept(previous, proposal, terrain), executor)
          .whenComplete(
              (unused, error) -> {
                if (error != null)
                  deferred(previous, proposal, "Fresh survey unavailable: " + error.getMessage());
                pending.remove(previous.id());
              });
    } catch (RuntimeException e) {
      deferred(previous, proposal, "Survey could not start: " + e.getMessage());
      pending.remove(previous.id());
    }
  }

  private void deferred(Settlement previous, DesignProposal proposal, String reason) {
    connections.read(
        () -> {
          Settlement v = current(previous);
          if (v == null || closed) return;
          var waiting =
              DesignProposals.defer(v, proposal, reason, System.currentTimeMillis() + 2 * interval);
          write(v, "proposal", waiting);
          feedback(v, "Retained " + proposal.kind() + ": " + reason);
          next.put(v.id(), System.currentTimeMillis() + interval);
        });
  }

  private synchronized DesignProposals.Admission accept(
      Settlement previous, DesignProposal proposal, Terrain terrain) {
    java.util.concurrent.atomic.AtomicReference<DesignProposals.Admission> admission =
        new java.util.concurrent.atomic.AtomicReference<>();
    connections.read(
        () -> {
          Settlement v = current(previous);
          if (v == null || closed) return;
          var result =
              DesignProposals.admit(
                  v,
                  proposal,
                  terrain,
                  occupied(v),
                  activeLimit,
                  System.currentTimeMillis() + 2 * interval);
          admission.set(result);
          next.put(v.id(), System.currentTimeMillis() + interval);
          if (!result.accepted()) {
            write(v, "proposal", result.proposal());
            feedback(v, "Retained " + proposal.kind() + ": " + result.proposal().reason());
            return;
          }
          if (result.compiled() != null) {
            write(
                v,
                "plan",
                Map.of(
                    "design",
                    result.design(),
                    "jobs",
                    result.compiled().jobs(),
                    "reserved",
                    result.compiled().reservations(),
                    "construction",
                    result.compiled().construction(),
                    "proposal",
                    proposal));
            feedback(
                v,
                "Accepted "
                    + result.design().project()
                    + ": "
                    + result.design().purpose()
                    + "; "
                    + result.design().jobs()
                    + " actions; materials "
                    + result.design().materials());
          }
        });
    return admission.get();
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
      You are the village architect. Propose one useful project from supported kinds using observations, needs, resources and prior failures. Return a JSON blueprint. Declare coordinate_space: relative for offsets from survey_origin or world for absolute world positions. The host converts explicitly declared world coordinates to offsets before validation. Prefer relative offsets. Survey_origin is an inhabited work area rather than necessarily the old settlement center. Examples are optional suggestions, not a whitelist: you may change coordinates, dimensions and routes. There are no configured numeric proposal ranges. World checks validate actual support, access, resources, collision and protected blocks. Larger work may need independently buildable stages when execution resources are exhausted; explain the useful first stage rather than claiming a whole village is finished.
      Respond wait if no physically meaningful project is available. Recent hostile mobs and missing defenses are urgent: propose a local wall protecting an inhabited bed/storage area even when other houses remain unfinished. A merged village can have multiple local defenses. A wall need not include every distant chest or the historic village center. Do not build a wall around nothing. retained_proposals are remembered intentions, not completed work. Address their recorded blocker or propose a revised layout; capacity and unavailable observations can be temporary. A duplicate closing corner is accepted, and the host can relocate/orient a gate on its nearest straight segment while retaining your contour. Avoid repeating the same failed geometry. site_rejections provides actual reasons candidates failed.
      house: x/z minimum corner, width/depth footprint, height walls, direction entrance, optional points bed feet facing south. Leave headroom and room for the two-block beds. wall: simple closed axis-aligned polygon points, x/z a gate point on a straight segment, height positive, direction gate orientation. path: ordered axis-aligned points and width1. farm: x/z footprint, width/depth; needs soil and existing water. lights: points with support. mine: x/z entrance, direction staircase, depth descending steps and width horizontal continuation. Workers need actual tools and materials. Survey observations, not model recollection, determine what exists. A zero-coal mine is exploration, not guaranteed fuel. End with the final blueprint; never attest completed world work.
      """;
}
