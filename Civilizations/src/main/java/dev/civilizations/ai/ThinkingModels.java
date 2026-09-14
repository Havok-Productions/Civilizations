package dev.civilizations.ai;

import java.util.Map;
import java.util.function.BiConsumer;

/** One queue owns both engines. Routine execution never passes through this boundary. */
public final class ThinkingModels implements ModelBackend {
  private final ModelBackend primary, deep;
  private final BiConsumer<String, Map<String, ?>> events;
  private ModelBackend last;

  /** Read by a response parser on the single inference thread, including retries. */
  public boolean lastWasDeep() {
    return deep != null && last == deep;
  }

  public ThinkingModels(
      ModelBackend primary, ModelBackend deep, BiConsumer<String, Map<String, ?>> events) {
    this.primary = primary;
    this.deep = deep;
    this.events = events;
  }

  public boolean ready() {
    return primary.ready() || deep != null && deep.ready();
  }

  public String status() {
    return "Qwen: "
        + primary.status()
        + "; deeper thinker: "
        + (deep == null ? "disabled" : deep.status());
  }

  public String complete(String system, String user) throws Exception {
    return complete(system, user, ReasoningMode.NORMAL, null, Long.MAX_VALUE);
  }

  public String complete(
      String system, String user, ReasoningMode mode, String schema, long deadline)
      throws Exception {
    last =
        deep != null && deep.ready() && (mode == ReasoningMode.RECOVERY || !primary.ready())
            ? deep
            : primary;
    events.accept(
        "model_route", Map.of("model", last == deep ? "deep" : "primary", "reason", mode.name()));
    // Leave time for the second engine if the initial design response stalls or is invalid.
    long firstDeadline =
        last == primary && deep != null && deep.ready()
            ? Math.min(deadline, System.currentTimeMillis() + 45_000)
            : deadline;
    return last.complete(system, user, mode, schema, firstDeadline);
  }

  public String recover(String system, String user, String schema, long deadline) throws Exception {
    ModelBackend fallback = last == primary && deep != null && deep.ready() ? deep : primary;
    if (!fallback.ready()) throw new java.io.IOException("No recovery model ready");
    last = fallback;
    events.accept(
        "model_route",
        Map.of(
            "model",
            fallback == deep ? "deep" : "primary",
            "reason",
            "previous response failed validation or timed out"));
    return fallback.complete(
        system,
        user,
        fallback == deep ? ReasoningMode.RECOVERY : ReasoningMode.FINAL,
        schema,
        deadline);
  }

  public void close() {
    primary.close();
    if (deep != null) deep.close();
  }
}
