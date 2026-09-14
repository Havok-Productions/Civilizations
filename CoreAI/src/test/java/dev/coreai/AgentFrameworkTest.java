package dev.coreai;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import dev.coreai.agent.*;
import dev.coreai.reasoning.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class AgentFrameworkTest {
  private static AgentFrame frame(String agent, long time) {
    var observation =
        new Observation(agent, "warehouse", time, Facts.of(Map.of("boxes_at_source", 1)));
    var goal =
        new Goal("delivery", "Move a box to storage", Facts.of(Map.of("boxes_at_storage", 1)));
    var action = new Action("move-box", goal.id(), "warehouse:move", Facts.of(Map.of("count", 1)));
    return new AgentFrame(observation, List.of(goal), List.of(action));
  }

  private static Outcome delivered() {
    return new Outcome(
        Outcome.Status.SUCCEEDED,
        "storage_count_observed",
        Facts.of(Map.of("boxes_at_storage", 1)));
  }

  @Test
  @Tag("coreai")
  void independentHostPerceivesPlansExecutesAndRemembersWithoutAWorldOrModel() {
    List<AgentSession.Experience> persisted = new ArrayList<>();
    var memory = new RecentMemory("porter", 4, persisted::add);
    var session = new AgentSession("porter", memory, () -> 20, fail -> fail(fail));
    var original = frame("porter", 10);
    session.observe(original::observation, observation -> original);
    var completion = new CompletableFuture<Outcome>();
    AtomicInteger executions = new AtomicInteger();
    assertFalse(
        session.start(
            "invented-action",
            attempt -> {
              executions.incrementAndGet();
              return completion;
            }));
    assertTrue(
        session.start(
            "move-box",
            attempt -> {
              executions.incrementAndGet();
              return completion;
            }));
    assertFalse(session.start("move-box", attempt -> completion));
    assertTrue(session.recent().isEmpty(), "A submitted action is not a success");
    completion.complete(delivered());
    assertEquals(1, executions.get());
    assertEquals(1, persisted.size());
    assertEquals(original.observation(), persisted.getFirst().attempt().observation());
    assertEquals(Outcome.Status.SUCCEEDED, persisted.getFirst().outcome().status());
    assertFalse(session.start("move-box", attempt -> completion), "A new observation is required");
    assertTrue(memory.recent("another-agent").isEmpty());
    assertThrows(IllegalArgumentException.class, () -> session.observe(frame("porter", 9)));
    session.close();
  }

  @Test
  @Tag("coreai")
  void cancellationAndLateSuccessCannotOverwriteANewActionOrLabelCancellationAsFailure() {
    var session =
        new AgentSession("porter", new RecentMemory("porter", 4, e -> {}), () -> 20, e -> fail(e));
    session.observe(frame("porter", 10));
    var old = new CompletableFuture<Outcome>();
    session.start("move-box", attempt -> old);
    session.cancel("host stopped action");
    session.observe(frame("porter", 11));
    var current = new CompletableFuture<Outcome>();
    session.start("move-box", attempt -> current);
    old.complete(delivered());
    assertNotNull(session.active());
    assertEquals(1, session.recent().size());
    assertEquals(Outcome.Status.CANCELLED, session.recent().getFirst().outcome().status());
    current.complete(delivered());
    assertEquals(2, session.recent().size());
    assertNotEquals(session.recent().get(0).attempt().id(), session.recent().get(1).attempt().id());
    session.close();
    assertThrows(IllegalStateException.class, () -> session.observe(frame("porter", 12)));
  }

  @Test
  @Tag("coreai")
  void nestedEvidenceIsFrozenAndExecutorExceptionsRemainFailures() {
    List<String> resources = new ArrayList<>(List.of("wood"));
    Facts facts = Facts.of(Map.of("resources", resources));
    resources.clear();
    facts.object().remove("resources");
    assertEquals(1, facts.object().getAsJsonArray("resources").size());
    String encoded = new Gson().toJson(facts);
    assertTrue(encoded.contains("resources"));
    assertFalse(
        encoded.contains("\\\"resources"), "Facts are objects, not double-encoded JSON strings");
    assertEquals(facts, new Gson().fromJson(encoded, Facts.class));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Outcome(Outcome.Status.SUCCEEDED, "model said so", Facts.of(Map.of())));
    var session =
        new AgentSession("porter", new RecentMemory("porter", 2, e -> {}), () -> 20, e -> fail(e));
    assertThrows(IllegalArgumentException.class, () -> session.observe(frame("other", 10)));
    session.observe(frame("porter", 10));
    session.start(
        "move-box",
        attempt -> {
          throw new IllegalStateException("Actuator unavailable");
        });
    assertNull(session.active());
    assertEquals(Outcome.Status.FAILED, session.recent().getFirst().outcome().status());
  }

  @Test
  @Tag("coreai")
  @Tag("inference")
  @Tag("interaction")
  void reusableSchedulerCallsAReplaceableBackendOnlyWhenRequested() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<ReasoningBackend.Request> received = new AtomicReference<>();
    ReasoningBackend backend =
        new ReasoningBackend() {
          public boolean ready() {
            return true;
          }

          public String status() {
            return "warehouse-test-backend";
          }

          public String complete(Request request) {
            calls.incrementAndGet();
            received.set(request);
            return "storage";
          }

          public void close() {}
        };
    try (var scheduler = new InferenceScheduler(backend, 2)) {
      assertEquals(0, calls.get());
      CompletableFuture<String> reply = new CompletableFuture<>();
      assertTrue(
          scheduler.submit(
              "porter",
              "Choose destination",
              "observed empty shelf",
              ReasoningBackend.Purpose.RECOVERY,
              "{}",
              System.currentTimeMillis() + 5000,
              text -> text,
              reply::complete));
      assertEquals("storage", reply.get(3, TimeUnit.SECONDS));
      assertEquals(1, calls.get());
      assertEquals(ReasoningBackend.Purpose.RECOVERY, received.get().purpose());
      assertEquals("observed empty shelf", received.get().observations());
    }
  }
}
