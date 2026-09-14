package dev.coreai.reasoning;

import java.util.Objects;

/** Replaceable local inference transport. Invoke only on an inference executor, not a game tick. */
public interface ReasoningBackend extends AutoCloseable {
  enum Purpose {
    NORMAL,
    RECOVERY,
    DESIGN,
    FINAL
  }

  record Request(
      String system, String observations, Purpose purpose, String schema, long deadline) {
    public Request {
      Objects.requireNonNull(system);
      Objects.requireNonNull(observations);
      Objects.requireNonNull(purpose);
      Objects.requireNonNull(schema);
    }
  }

  boolean ready();

  String status();

  String complete(Request request) throws Exception;

  default String recover(Request request) throws Exception {
    return complete(
        new Request(
            request.system(),
            request.observations(),
            Purpose.FINAL,
            request.schema(),
            request.deadline()));
  }

  @Override
  void close();
}
