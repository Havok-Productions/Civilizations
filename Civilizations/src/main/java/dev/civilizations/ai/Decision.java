package dev.civilizations.ai;

import com.google.gson.*;
import java.util.Set;

public record Decision(String action, String jobId, String material, String reason) {
  public static Decision parse(String text, Set<String> offeredJobs) {
    JsonObject j = JsonParser.parseString(text).getAsJsonObject();
    String action = j.get("action").getAsString(),
        job = j.has("job_id") ? j.get("job_id").getAsString() : "";
    String material = j.has("material") ? j.get("material").getAsString() : "COBBLESTONE";
    String reason = j.has("reason") ? j.get("reason").getAsString() : "";
    if (!Set.of("work", "gather", "deposit", "rest", "replan").contains(action))
      throw new IllegalArgumentException("Unsupported action");
    if (action.equals("work") && !offeredJobs.contains(job))
      throw new IllegalArgumentException("Job not offered");
    if (action.equals("gather")
        && !Set.of("COBBLESTONE", "LOG", "OAK_LOG", "COAL", "WHITE_WOOL", "WHEAT_SEEDS")
            .contains(material)) throw new IllegalArgumentException("Unsupported resource");
    return new Decision(action, job, material, reason.substring(0, Math.min(200, reason.length())));
  }
}
