package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import dev.civilizations.design.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContinuityTest {
  @TempDir Path directory;

  private Settlement reload(Settlement village) throws Exception {
    StateStore store = new StateStore(directory);
    store.save(List.of(village.snapshot()));
    return store.load(message -> fail(message)).getFirst();
  }

  @Test
  @Tag("tasks")
  @Tag("settlements")
  @Tag("interaction")
  void interruptedSupplyChainSurvivesRestartAndReturnsToItsParentWithoutStealingClaims()
      throws Exception {
    Settlement v = CoreTest.village();
    v.enroll("worker", 5);
    v.enroll("other", 5);
    Job parent = CoreTest.job(new Pos(2, 65, 2));
    Job child =
        new Job(
            Job.Kind.MINE,
            "mine",
            new Pos(8, 63, 2),
            new Pos(7, 64, 2),
            "COBBLESTONE",
            "STONE",
            null);
    v.addProject(parent.project, List.of(parent));
    v.addProject(child.project, List.of(child));
    assertTrue(v.claim(parent.id, "worker", 100));
    assertTrue(v.claim(child.id, "worker", 100));
    v.checkpoint("worker", parent, null, "Working");
    v.checkpoint("worker", child, parent, "Collecting stone");
    v.taskProject("worker", parent.project);
    v.taskProject("worker", child.project);
    v.release("worker"); // Bed time, threat, unload and stop release leases but retain intent.
    v = reload(v);
    assertEquals(1, v.checkpoints("worker").size());
    assertFalse(v.mayShareSurplus("worker"));
    assertTrue(v.claim(parent.id, "other", 200));
    assertNull(v.resume("worker", Set.of(child.id), 201));
    assertTrue(
        v.available(child.id, "other", 201), "Failed resumption must not partly claim the child");
    v.release("other");
    var continuation = v.resume("worker", Set.of(child.id), 202);
    assertEquals(child.id, continuation.job().id);
    assertEquals(parent.id, continuation.parent().id);
    assertFalse(v.available(parent.id, "other", 203));
    assertFalse(v.available(child.id, "other", 203));
    assertTrue(v.done(child.id, "worker"));
    assertEquals(parent.id, v.checkpoints("worker").getFirst().job());
    assertTrue(v.done(parent.id, "worker"));
    v.checkpoint("worker", parent, null, "Stale reset callback");
    assertTrue(v.checkpoints("worker").isEmpty());
    assertTrue(v.mayShareSurplus("worker"));
  }

  @Test
  @Tag("tasks")
  @Tag("settlements")
  @Tag("interaction")
  void mergeKeepsExactStepsAndRetryEvidenceAndOffersRememberedWorkBeforeNewNearbyWork()
      throws Exception {
    Settlement a = CoreTest.village();
    Settlement.Data data = a.snapshot();
    data.id = UUID.randomUUID().toString();
    data.center = new Pos(20, 65, 0);
    Settlement b = new Settlement(data);
    b.enroll("worker", 5);
    Job remembered = CoreTest.job(new Pos(20, 65, 2));
    b.addProject(remembered.project, List.of(remembered));
    b.claim(remembered.id, "worker", 100);
    b.checkpoint("worker", remembered, null, "Interrupted");
    b.failed(remembered.id, "worker", 100, "Waiting for a loaded approach");
    Settlement merged = reload(VillageConnections.combine(List.of(a, b)));
    assertEquals(remembered.id, merged.checkpoints("worker").getFirst().job());
    assertNull(merged.resume("worker", Set.of(remembered.id), 101));
    Job saved = merged.jobs().getFirst();
    assertEquals(15100, saved.retryAfter);
    assertEquals("Waiting for a loaded approach", saved.blockedReason);
    Job nearby = CoreTest.job(new Pos(1, 65, 1));
    nearby.project = "nearby";
    merged.addProject(nearby.project, List.of(nearby));
    var offered =
        TaskSelection.offered(merged, "worker", new Pos(0, 65, 0), Map.of("COBBLESTONE", 8), 15101);
    assertEquals(remembered.id, offered.getFirst().id);
    assertNotNull(merged.resume("worker", Set.of(remembered.id), 15101));
  }

  @Test
  @Tag("design")
  void closedPolygonAndCornerGateAreNormalizedWithoutChangingContourOrSkippingCollisionChecks() {
    var points =
        List.of(
            DesignTest.p(-3, -3),
            DesignTest.p(3, -3),
            DesignTest.p(3, -3),
            DesignTest.p(3, 3),
            DesignTest.p(-3, 3),
            DesignTest.p(-3, -3));
    Blueprint original = new Blueprint("wall", "Protect village", -3, -3, 0, 0, 3, "north", points);
    var prepared = BlueprintNormalization.prepare(original);
    assertEquals(4, prepared.blueprint().points().size());
    assertEquals(2, prepared.adjustments().size());
    var result = DesignTest.compile(prepared.blueprint(), new CoreTest.Flat());
    assertEquals(70, result.jobs().size());
    CoreTest.Flat wet = new CoreTest.Flat();
    wet.overrides.put(new Pos(3, 64, 0), "WATER");
    assertThrows(
        IllegalArgumentException.class, () -> DesignTest.compile(prepared.blueprint(), wet));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DesignCompiler()
                .compile(
                    prepared.blueprint(),
                    new CoreTest.Flat(),
                    new Pos(0, 65, 0),
                    "protected",
                    p -> p.equals(new Pos(3, 65, 0)),
                    List.of()));
    Blueprint crossing =
        new Blueprint(
            "wall",
            "Crossing",
            0,
            0,
            0,
            0,
            3,
            "north",
            List.of(
                DesignTest.p(-3, -3),
                DesignTest.p(3, -3),
                DesignTest.p(3, 3),
                DesignTest.p(0, 3),
                DesignTest.p(0, -5),
                DesignTest.p(-3, -5)));
    assertThrows(IllegalArgumentException.class, () -> BlueprintNormalization.prepare(crossing));
    Blueprint diagonal =
        new Blueprint(
            "path",
            "Route",
            0,
            0,
            1,
            0,
            0,
            "north",
            List.of(DesignTest.p(0, 0), DesignTest.p(3, 3)));
    assertThrows(IllegalArgumentException.class, () -> BlueprintNormalization.prepare(diagonal));
  }

  @Test
  @Tag("design")
  @Tag("settlements")
  @Tag("interaction")
  void capacityDeferredProposalSurvivesRestartMergeAndIsAdmittedOnceWhenSlotOpens()
      throws Exception {
    Settlement v = CoreTest.village();
    v.enroll("worker", 5);
    var p = DesignProposals.retain(v, DesignTest.house(3, 3, 5, 5, "north"), v.center(), 100);
    var deferred = DesignProposals.admit(v, p, new CoreTest.Flat(), q -> false, 0, 200);
    assertFalse(deferred.accepted());
    assertEquals("waiting", deferred.proposal().status());
    assertTrue(v.jobs().isEmpty());
    v = reload(v);
    Settlement.Data other = v.snapshot();
    other.id = UUID.randomUUID().toString();
    other.proposals = new ArrayList<>();
    other.agents = new HashMap<>();
    v = VillageConnections.combine(List.of(v, new Settlement(other)));
    p = v.proposals().getFirst();
    assertEquals(deferred.proposal(), p);
    assertFalse(p.due(199));
    assertTrue(p.due(200));
    var accepted = DesignProposals.admit(v, p, new CoreTest.Flat(), q -> false, 2, 300);
    assertTrue(accepted.accepted(), accepted.proposal().reason());
    assertTrue(v.proposals().isEmpty());
    assertEquals(DesignProposals.project(p), accepted.design().project());
    int jobs = v.jobs().size();
    assertTrue(DesignProposals.admit(v, p, new CoreTest.Flat(), q -> false, 2, 400).accepted());
    assertEquals(jobs, v.jobs().size());
    assertEquals(1, v.designs().size());
  }

  @Test
  @Tag("design")
  @Tag("settlements")
  @Tag("interaction")
  void unavailableTerrainRetainsOriginalProposalAndFreshSurveyCanAdmitIt() throws Exception {
    Settlement v = CoreTest.village();
    v.enroll("worker", 5);
    Blueprint blueprint = DesignTest.house(3, 3, 5, 5, "north");
    var p = DesignProposals.retain(v, blueprint, v.center(), 100);
    var unavailable =
        new CoreTest.Flat() {
          public boolean available(int x, int z) {
            return false;
          }
        };
    var deferred = DesignProposals.admit(v, p, unavailable, q -> false, 2, 200);
    assertFalse(deferred.accepted());
    assertTrue(deferred.proposal().reason().contains("not observed"));
    v = reload(v);
    p = v.proposals().getFirst();
    assertEquals(blueprint, Blueprint.parse(p.original()));
    Blueprint reworded =
        new Blueprint(
            blueprint.kind(),
            "Reworded purpose",
            blueprint.x(),
            blueprint.z(),
            blueprint.width(),
            blueprint.depth(),
            blueprint.height(),
            blueprint.direction(),
            blueprint.points());
    assertEquals(p.id(), DesignProposals.retain(v, reworded, v.center(), 201).id());
    assertEquals(1, v.proposals().size());
    assertTrue(DesignProposals.admit(v, p, new CoreTest.Flat(), q -> false, 2, 300).accepted());
  }

  @Test
  @Tag("design")
  void invalidGeometryIsRetainedForRevisionInsteadOfBeingSilentlyDropped() {
    Settlement v = CoreTest.village();
    Blueprint diagonal =
        new Blueprint(
            "path",
            "Connect farm",
            0,
            0,
            1,
            0,
            0,
            "north",
            List.of(DesignTest.p(0, 0), DesignTest.p(3, 3)));
    var p = DesignProposals.retain(v, diagonal, v.center(), 100);
    assertEquals("needs_revision", p.status());
    assertTrue(p.due(100));
    assertTrue(p.needsSalvage());
    assertEquals(diagonal, Blueprint.parse(p.original()));
    assertEquals(p, v.proposals().getFirst());
  }

  @Test
  @Tag("design")
  @Tag("tasks")
  @Tag("interaction")
  void workStandingSpaceCanBeSharedWhileConstructionAndInteriorReservationsRemainExclusive() {
    Blueprint light =
        new Blueprint(
            "lights", "Light work area", 0, 0, 0, 0, 0, "north", List.of(DesignTest.p(3, 3)));
    var baseline = DesignTest.compile(light, new CoreTest.Flat());
    Pos stand = baseline.jobs().getFirst().stand;
    Set<Pos> access = Set.of(stand, stand.add(0, -1, 0), stand.add(0, 1, 0));
    var compiled =
        new DesignCompiler()
            .compile(
                light,
                new CoreTest.Flat(),
                new Pos(0, 65, 0),
                "design-lights-shared",
                access::contains,
                List.of());
    assertTrue(Collections.disjoint(access, compiled.construction()));
    assertTrue(compiled.reservations().containsAll(access));
    Settlement v = CoreTest.village();
    v.enroll("worker", 5);
    Job prior = CoreTest.job(new Pos(20, 65, 20));
    prior.stand = stand;
    v.addProject(prior.project, List.of(prior));
    DesignRecord record =
        new DesignRecord("design-lights-shared", "lights", "Shared access", "{}", Map.of(), 1, 100);
    assertTrue(
        v.addDesign(record, compiled.jobs(), compiled.reservations(), compiled.construction(), 2));
    Pos target = compiled.jobs().getFirst().target;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DesignCompiler()
                .compile(
                    light,
                    new CoreTest.Flat(),
                    new Pos(0, 65, 0),
                    "collision",
                    target::equals,
                    List.of()));
    Blueprint house = DesignTest.house(3, 3, 5, 5, "north");
    var sharedMargin =
        new DesignCompiler()
            .compile(
                house,
                new CoreTest.Flat(),
                new Pos(0, 65, 0),
                "house-margin",
                p -> p.x() == 2,
                List.of());
    assertTrue(sharedMargin.construction().stream().noneMatch(p -> p.x() == 2));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DesignCompiler()
                .compile(
                    house,
                    new CoreTest.Flat(),
                    new Pos(0, 65, 0),
                    "house-interior",
                    p -> p.equals(new Pos(5, 67, 5)),
                    List.of()));
  }
}
