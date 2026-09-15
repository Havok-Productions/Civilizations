package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.civilizations.ai.*;
import dev.civilizations.core.*;
import dev.civilizations.design.*;
import dev.civilizations.world.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProposalSalvageTest {
  @TempDir Path directory;

  @Test
  @Tag("design")
  @Tag("inference")
  @Tag("interaction")
  void retainedWallOutsideOriginalContextGetsCompleteSurveyBeforeRevalidation() throws Exception {
    Settlement v = CoreTest.village();
    v.enroll("worker", 5);
    Pos origin = v.center().add(26, 0, 52);
    Blueprint b =
        new Blueprint(
            "wall",
            "Protect existing work center",
            -26,
            -56,
            0,
            0,
            2,
            "north",
            List.of(
                DesignTest.p(-30, -56),
                DesignTest.p(-22, -56),
                DesignTest.p(-22, -48),
                DesignTest.p(-30, -48)));
    var saved =
        DesignProposals.retain(v, b, origin, 0)
            .waiting("needs_revision", "Terrain not observed at -26,-52", 0);
    v.proposal(saved);
    v.beds(List.of(v.center()));
    List<Integer> radii = new CopyOnWriteArrayList<>();
    Backend backend = new Backend();
    try (var queue = new InferenceQueue(backend, 4);
        var coordinator =
            new DesignCoordinator(
                queue,
                new VillageConnections(),
                (world, center, radius) -> {
                  radii.add(radius);
                  return CompletableFuture.completedFuture(
                      new CoreTest.Flat() {
                        public boolean available(int x, int z) {
                          return Math.abs(x - center.x()) <= radius
                              && Math.abs(z - center.z()) <= radius;
                        }

                        public String type(Pos p) {
                          return available(p.x(), p.z()) ? super.type(p) : "UNKNOWN";
                        }
                      });
                },
                Runnable::run,
                () -> List.of(v),
                directory,
                1000,
                2,
                message -> {})) {
      coordinator.consider(v, null, new CoreTest.Flat(), Map.of());
      assertEquals(List.of(b.surveyRadius()), radii);
      assertTrue(v.proposals().isEmpty(), v.designFeedback().toString());
      assertEquals(DesignProposals.project(saved), v.designs().getFirst().project());
      assertNull(backend.request.get());
    }
  }

  @Test
  @Tag("design")
  @Tag("inference")
  @Tag("interaction")
  void exhaustedFullSurveyUsesSmallMapOnlyForRevisionWithoutOverwritingRealFailure() {
    Settlement v = CoreTest.village();
    v.enroll("worker", 5);
    Blueprint b = DesignTest.house(4100, 1200, 5, 5, "north");
    var saved =
        DesignProposals.retain(v, b, v.center(), 0)
            .waiting("needs_revision", "snapshot_resource_budget_exceeded", 0);
    v.proposal(saved);
    List<Integer> radii = new ArrayList<>();
    try (var queue = new InferenceQueue(new Backend(), 4);
        var coordinator =
            new DesignCoordinator(
                queue,
                new VillageConnections(),
                (world, center, radius) -> {
                  radii.add(radius);
                  if (radius > 32)
                    return CompletableFuture.failedFuture(
                        new RejectedExecutionException("snapshot_resource_budget_exceeded"));
                  return CompletableFuture.completedFuture(
                      new CoreTest.Flat() {
                        public String type(Pos p) {
                          return p.y() == 64 ? "WATER" : super.type(p);
                        }
                      });
                },
                Runnable::run,
                () -> List.of(v),
                directory,
                1000,
                2,
                message -> {})) {
      coordinator.consider(v, null, new CoreTest.Flat(), Map.of());
      assertEquals(List.of(b.surveyRadius(), 32), radii);
      assertTrue(v.jobs().isEmpty());
      assertTrue(v.proposals().getFirst().reason().contains("snapshot_resource_budget_exceeded"));
      assertFalse(v.proposals().getFirst().reason().contains("Terrain not observed"));
    }
  }

  private static class Backend implements ModelBackend {
    boolean online;
    Blueprint answer;
    AtomicReference<String> request = new AtomicReference<>();
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(0);

    public boolean ready() {
      return online;
    }

    public String status() {
      return "test";
    }

    public void close() {}

    public String complete(String system, String user) throws Exception {
      request.set(user);
      started.countDown();
      if (!release.await(5, TimeUnit.SECONDS))
        throw new IllegalStateException("Test release timed out");
      return new Gson().toJson(answer);
    }
  }

  private DesignCoordinator coordinator(
      InferenceQueue queue,
      VillageConnections connections,
      Terrain terrain,
      Collection<Settlement> villages,
      CountDownLatch accepted) {
    DesignCoordinator coordinator =
        new DesignCoordinator(
            queue,
            connections,
            (world, origin, radius) -> CompletableFuture.completedFuture(terrain),
            Runnable::run,
            () -> villages,
            directory.resolve("designs"),
            1000,
            2,
            message -> {});
    coordinator.observe(
        (v, event) -> {
          if ("validation".equals(event.get("stage"))
              && String.valueOf(event.get("message")).startsWith("Accepted ")) accepted.countDown();
        });
    return coordinator;
  }

  @Test
  @Tag("design")
  @Tag("inference")
  @Tag("settlements")
  @Tag("interaction")
  void targetedModelSalvagesSameGoalAcrossMergeAndCompilesActualPreparationBeforeBuilding()
      throws Exception {
    Settlement village = CoreTest.village();
    village.enroll("worker", 5);
    Blueprint original = DesignTest.house(3, 3, 5, 5, "north");
    DesignProposal proposal = DesignProposals.retain(village, original, village.center(), 0);
    CoreTest.Flat terrain = new CoreTest.Flat();
    terrain.overrides.put(
        new Pos(5, 66, 5), "OAK_PLANKS"); // Existing structure blocks original footprint.
    terrain.overrides.put(
        new Pos(12, 65, 4), "OAK_LOG"); // Revised footprint requires real clearing.
    terrain.overrides.put(new Pos(12, 66, 4), "OAK_LEAVES"); // Canopy identifies a natural tree.
    terrain.overrides.put(new Pos(13, 65, 5), "DIRT");
    proposal = DesignProposals.admit(village, proposal, terrain, q -> false, 2, 0).proposal();
    assertTrue(proposal.needsSalvage());
    String project = DesignProposals.project(proposal);
    List<Settlement> villages = new CopyOnWriteArrayList<>(List.of(village));
    VillageConnections connections = new VillageConnections();
    Backend backend = new Backend();
    backend.online = true;
    backend.answer = DesignTest.house(10, 3, 5, 5, "north");
    backend.release = new CountDownLatch(1);
    CountDownLatch accepted = new CountDownLatch(1);
    try (InferenceQueue queue = new InferenceQueue(backend, 4);
        DesignCoordinator coordinator =
            coordinator(queue, connections, terrain, villages, accepted)) {
      coordinator.consider(village, null, terrain, Map.of("LOG", 1));
      assertTrue(backend.started.await(5, TimeUnit.SECONDS));
      var request = JsonParser.parseString(backend.request.get()).getAsJsonObject();
      assertEquals(
          proposal.id(),
          request.getAsJsonObject("salvage_existing_proposal").get("proposal_id").getAsString());
      assertEquals(List.of("house"), new Gson().fromJson(request.get("allowed_kinds"), List.class));
      Settlement.Data data = CoreTest.village().snapshot();
      data.world = village.world();
      Settlement merged =
          connections.change(
              () -> {
                Settlement result =
                    VillageConnections.combine(List.of(village, new Settlement(data)));
                village.retire();
                villages.clear();
                villages.add(result);
                return result;
              });
      backend.release.countDown();
      assertTrue(
          accepted.await(5, TimeUnit.SECONDS),
          () -> merged.designFeedback() + "; " + queue.status());
      assertTrue(merged.proposals().isEmpty());
      assertEquals(1, merged.designs().size());
      assertEquals(project, merged.designs().getFirst().project());
      assertEquals(original.purpose(), merged.designs().getFirst().purpose());
      Job clearing =
          merged.jobs().stream()
              .filter(j -> j.kind == Job.Kind.CLEAR && j.target.equals(new Pos(12, 65, 4)))
              .findFirst()
              .orElseThrow();
      assertEquals(new Pos(12, 65, 4), clearing.target);
      assertTrue(
          merged.jobs().stream()
              .filter(j -> j.kind == Job.Kind.PLACE)
              .allMatch(j -> j.phase > clearing.phase));
      assertTrue(
          merged.jobs().stream().noneMatch(j -> j.complete),
          "A compiled salvage is not completed world work");
      String plan =
          Files.readString(directory.resolve("designs").resolve(merged.id() + "-plan.json"));
      assertTrue(plan.contains("history"));
      assertTrue(plan.contains("Physical validation"));
    } finally {
      backend.release.countDown();
    }
  }

  @Test
  @Tag("design")
  @Tag("inference")
  @Tag("interaction")
  void offlineModelUsesObservedAlternativeForInvalidWallWithoutLosingOriginalIdentity()
      throws Exception {
    Settlement village = CoreTest.village();
    village.enroll("worker", 5);
    Blueprint invalid =
        new Blueprint(
            "wall",
            "Protect the homes",
            0,
            -4,
            0,
            0,
            3,
            "north",
            List.of(
                DesignTest.p(-4, -4),
                DesignTest.p(4, -3),
                DesignTest.p(4, 4),
                DesignTest.p(-4, 4)));
    DesignProposal saved = DesignProposals.retain(village, invalid, village.center(), 0);
    assertTrue(saved.needsSalvage());
    Backend offline = new Backend();
    CountDownLatch accepted = new CountDownLatch(1);
    try (InferenceQueue queue = new InferenceQueue(offline, 4);
        DesignCoordinator coordinator =
            coordinator(
                queue, new VillageConnections(), new CoreTest.Flat(), List.of(village), accepted)) {
      coordinator.consider(village, null, new CoreTest.Flat(), Map.of());
      assertTrue(accepted.await(5, TimeUnit.SECONDS));
      assertNull(offline.request.get());
      assertEquals(1, village.designs().size());
      assertEquals(DesignProposals.project(saved), village.designs().getFirst().project());
      assertEquals(invalid.purpose(), village.designs().getFirst().purpose());
      assertTrue(village.proposals().isEmpty());
      assertTrue(village.jobs().stream().anyMatch(j -> j.material.equals("OAK_FENCE_GATE")));
    }
  }

  @Test
  @Tag("design")
  @Tag("inference")
  @Tag("interaction")
  void freshlyClearedSiteUsesRetainedPlanBeforeRequestingAnotherModelRevision() throws Exception {
    Settlement v = CoreTest.village();
    v.enroll("worker", 5);
    Blueprint original = DesignTest.house(3, 3, 5, 5, "north");
    var saved = DesignProposals.retain(v, original, v.center(), 0);
    saved = saved.waiting("needs_revision", "Physical validation: old obstruction", 0);
    v.proposal(saved);
    Backend backend = new Backend();
    backend.online = true;
    CountDownLatch accepted = new CountDownLatch(1);
    try (InferenceQueue queue = new InferenceQueue(backend, 4);
        DesignCoordinator coordinator =
            coordinator(
                queue, new VillageConnections(), new CoreTest.Flat(), List.of(v), accepted)) {
      coordinator.consider(v, null, new CoreTest.Flat(), Map.of());
      assertTrue(accepted.await(5, TimeUnit.SECONDS));
      assertNull(backend.request.get());
      assertEquals(original, Blueprint.parse(v.designs().getFirst().blueprint()));
      assertEquals(DesignProposals.project(saved), v.designs().getFirst().project());
    }
  }

  @Test
  @Tag("design")
  @Tag("settlements")
  @Tag("interaction")
  void revisionHistoryAndOriginalSurviveRestartAndOldSavedProposalsStillLoad() throws Exception {
    Settlement v = CoreTest.village();
    v.enroll("worker", 5);
    Blueprint original = DesignTest.house(3, 3, 5, 5, "north");
    var saved = DesignProposals.retain(v, original, v.center(), 0);
    saved = saved.waiting("waiting", "Physical validation: blocked by existing structure", 100);
    var revised =
        ProposalSalvage.revise(
            saved, DesignTest.house(10, 3, 5, 5, "east"), "Try nearby ground", 200);
    v.proposal(revised);
    StateStore store = new StateStore(directory.resolve("state"));
    store.save(List.of(v.snapshot()));
    Settlement restored = store.load(message -> fail(message)).getFirst();
    var recovered = restored.proposals().getFirst();
    assertEquals(revised, recovered);
    assertEquals(saved.id(), recovered.id());
    assertEquals(original, Blueprint.parse(recovered.original()));
    assertTrue(
        recovered.history().stream().anyMatch(a -> a.reason().contains("existing structure")));
    var old = new Gson().toJsonTree(saved).getAsJsonObject();
    old.remove("history");
    assertTrue(new Gson().fromJson(old, DesignProposal.class).history().isEmpty());
    var repeated =
        saved.waiting("waiting", saved.reason(), 300).waiting("waiting", saved.reason(), 400);
    assertEquals(
        saved.history().size(),
        repeated.history().size(),
        "Repeated identical failure evidence is deduplicated");
    assertFalse(ProposalSalvage.usable(saved, original));
    Blueprint otherKind =
        new Blueprint("lights", "Other goal", 0, 0, 0, 0, 0, "north", List.of(DesignTest.p(0, 0)));
    assertFalse(ProposalSalvage.usable(saved, otherKind));
    assertEquals(
        saved.blueprint(), ProposalSalvage.revise(saved, otherKind, "Wrong goal", 500).blueprint());
  }

  @Test
  @Tag("design")
  @Tag("inference")
  @Tag("interaction")
  void noFeasibleRevisionStaysQueuedWithEvidenceAndNeverMarksWaterAsBuildable() {
    Settlement v = CoreTest.village();
    v.enroll("worker", 5);
    var saved = DesignProposals.retain(v, DesignTest.house(3, 3, 5, 5, "north"), v.center(), 0);
    CoreTest.Flat wet =
        new CoreTest.Flat() {
          public String type(Pos p) {
            return p.y() == 64 ? "WATER" : super.type(p);
          }
        };
    saved = DesignProposals.admit(v, saved, wet, q -> false, 2, 0).proposal();
    try (InferenceQueue queue = new InferenceQueue(new Backend(), 4);
        DesignCoordinator coordinator =
            coordinator(queue, new VillageConnections(), wet, List.of(v), new CountDownLatch(1))) {
      coordinator.consider(v, null, wet, Map.of());
      assertTrue(v.jobs().isEmpty());
      assertEquals(1, v.proposals().size());
      var retained = v.proposals().getFirst();
      assertEquals(saved.id(), retained.id());
      assertTrue(retained.needsSalvage());
      assertTrue(retained.due(Long.MAX_VALUE));
      assertFalse(retained.history().isEmpty());
    }
  }
}
