package dev.civilizations.ai;

import com.google.gson.*;
import java.io.IOException;

/** Reserves room for the answer before generation; only optional context may be removed. */
public final class PromptBudget {
  @FunctionalInterface
  public interface Counter {
    int tokens(JsonObject body) throws Exception;
  }

  public record Prepared(JsonObject body, int promptTokens, int outputTokens, int reductions) {}

  private PromptBudget() {}

  public static Prepared prepare(JsonObject request, int context, Counter counter)
      throws Exception {
    JsonObject body = request.deepCopy();
    int output = Math.min(body.get("max_tokens").getAsInt(), Math.max(256, context / 2));
    int reductions = 0;
    while (true) {
      int tokens = counter.tokens(body);
      if (tokens < 0) throw new IOException("Invalid prompt token count");
      if ((long) tokens + output + 64 <= context) {
        body.addProperty("max_tokens", output);
        return new Prepared(body, tokens, output, reductions);
      }
      JsonObject user = body.getAsJsonArray("messages").get(1).getAsJsonObject();
      String before = user.get("content").getAsString(), after = ModelContext.reduce(before);
      if (before.equals(after)) {
        int available = context - tokens - 64;
        if (available >= Math.min(1024, output)) {
          body.addProperty("max_tokens", available);
          return new Prepared(body, tokens, available, reductions);
        }
        // Keep current observations and an honest, explicit failure instead of malformed context.
        throw new IOException(
            "Prompt capacity exhausted: prompt="
                + tokens
                + ", output_reserve="
                + output
                + ", context="
                + context
                + "; essential current observations retained");
      }
      user.addProperty("content", after);
      reductions++;
    }
  }
}
