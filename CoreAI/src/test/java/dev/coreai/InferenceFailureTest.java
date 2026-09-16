package dev.coreai;

import static org.junit.jupiter.api.Assertions.*;

import dev.coreai.reasoning.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class InferenceFailureTest {
  @Test
  @Tag("coreai")
  @Tag("inference")
  @Tag("interaction")
  void eachRequestReportsAdmissionTransportAndParsingFailuresSeparately() throws Exception {
    for (String mode : new String[] {"offline", "transport", "invalid", "expired"}) {
      ReasoningBackend backend =
          new ReasoningBackend() {
            public boolean ready() {
              return !mode.equals("offline");
            }

            public String status() {
              return "local backend offline";
            }

            public String complete(Request request) throws Exception {
              if (mode.equals("transport")) throw new java.io.IOException("Connection refused");
              return "invalid";
            }

            public void close() {}
          };
      try (var scheduler = new InferenceScheduler(backend, 2)) {
        AtomicReference<InferenceScheduler.Failure> failure = new AtomicReference<>();
        CompletableFuture<String> value = new CompletableFuture<>();
        boolean admitted =
            scheduler.submit(
                "village",
                "",
                "",
                ReasoningBackend.Purpose.DESIGN,
                "{}",
                System.currentTimeMillis() + (mode.equals("expired") ? -1 : 10000),
                text -> {
                  throw new IllegalArgumentException("missing blueprint points");
                },
                value::complete,
                failure::set);
        if (admitted) assertNull(value.get(3, TimeUnit.SECONDS));
        assertEquals(
            switch (mode) {
              case "offline" -> "backend_unavailable";
              case "transport" -> "backend_error";
              case "expired" -> "expired";
              default -> "invalid_response";
            },
            failure.get().stage());
      }
    }
  }
}
