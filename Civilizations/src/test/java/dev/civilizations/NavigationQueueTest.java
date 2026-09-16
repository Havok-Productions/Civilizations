package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.navigation.NavigationQueue;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class NavigationQueueTest {
  @Tag("navigation")
  @Test
  void loadingBurstWaitsAndIdenticalRequestsShareWork() {
    var queue = new NavigationQueue<String, Integer>(4);
    List<CompletableFuture<Integer>> operations = new ArrayList<>(), results = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      String key = "worker-" + i;
      var result =
          queue.submit(
              key,
              "same-map",
              () -> {
                var operation = new CompletableFuture<Integer>();
                operations.add(operation);
                return operation;
              });
      results.add(result);
      assertSame(
          result,
          queue.submit(
              key,
              "same-map",
              () -> {
                throw new AssertionError("duplicate");
              }));
    }
    assertEquals(4, operations.size());
    for (int i = 0; i < 12; i++) {
      assertEquals(Math.min(12, i + 4), operations.size());
      operations.get(i).complete(i);
      assertEquals(i, results.get(i).join());
    }
    queue.close();
  }

  @Tag("navigation")
  @Test
  void supersededAndCancelledObserversDoNotReleaseRunningSlotsEarly() {
    var queue = new NavigationQueue<String, Integer>(1);
    var running = new CompletableFuture<Integer>();
    var old = queue.submit("worker", "old", () -> running);
    var superseded =
        queue.submit(
            "worker",
            "intermediate",
            () -> {
              throw new AssertionError("obsolete work");
            });
    var replacement = new CompletableFuture<Integer>();
    int[] started = {0};
    var newest =
        queue.submit(
            "worker",
            "new",
            () -> {
              started[0]++;
              return replacement;
            });
    assertTrue(old.isCancelled());
    assertTrue(superseded.isCancelled());
    assertEquals(0, started[0]);
    running.complete(1);
    assertEquals(1, started[0]);
    var cancelled =
        queue.submit(
            "other",
            "queued",
            () -> {
              throw new AssertionError("cancelled work");
            });
    queue.cancel("other");
    replacement.complete(2);
    assertEquals(2, newest.join());
    assertTrue(cancelled.isCancelled());
    queue.close();
  }

  @Tag("navigation")
  @Test
  void failureAndShutdownCompleteObserversWithoutLeakingAdmission() {
    var queue = new NavigationQueue<String, Integer>(1);
    assertThrows(
        CompletionException.class,
        () ->
            queue
                .submit(
                    "a",
                    1,
                    () -> {
                      throw new IllegalStateException("capture failed");
                    })
                .join());
    var active = queue.submit("a", 2, CompletableFuture::new);
    var waiting = queue.submit("b", 3, CompletableFuture::new);
    queue.close();
    assertTrue(active.isCancelled());
    assertTrue(waiting.isCancelled());
    assertThrows(
        CancellationException.class, () -> queue.submit("c", 4, CompletableFuture::new).join());
  }
}
