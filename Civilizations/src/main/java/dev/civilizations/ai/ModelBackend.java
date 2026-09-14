package dev.civilizations.ai;

/** Swappable inference boundary: callers supply only immutable text snapshots. */
public interface ModelBackend extends dev.coreai.reasoning.ReasoningBackend {
  @Override
  default String complete(dev.coreai.reasoning.ReasoningBackend.Request request) throws Exception {
    return complete(
        request.system(),
        request.observations(),
        ReasoningMode.valueOf(request.purpose().name()),
        request.schema(),
        request.deadline());
  }

  @Override
  default String recover(dev.coreai.reasoning.ReasoningBackend.Request request) throws Exception {
    return recover(request.system(), request.observations(), request.schema(), request.deadline());
  }

  boolean ready();

  String status();

  String complete(String system, String user) throws Exception;

  default String complete(String system, String user, ReasoningMode mode) throws Exception {
    return complete(system, user);
  }

  default String complete(String system, String user, ReasoningMode mode, String schema)
      throws Exception {
    return complete(system, user, mode);
  }

  default String complete(
      String system, String user, ReasoningMode mode, String schema, long deadline)
      throws Exception {
    return complete(system, user, mode, schema);
  }

  @Override
  void close();

  default String recover(String system, String user, String schema, long deadline)
      throws Exception {
    return complete(system, user, ReasoningMode.FINAL, schema, deadline);
  }
}
