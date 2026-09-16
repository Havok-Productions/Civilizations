package dev.civilizations.ai;

import com.google.gson.*;
import java.util.*;

/** Working snapshots for teachers. Full observations remain in the host's diagnostic journal. */
public final class ModelContext {
  private ModelContext() {}

  public static String compact(String report) {
    JsonObject root = object(report);
    if (root == null) return report;
    boolean design = root.has("terrain") && root.has("allowed_kinds");
    boolean worker = root.has("jobs") && root.has("inventory");
    boolean recovery = root.has("levels") && root.has("goal_relative");
    if (!design && !worker && !recovery) return report;
    if (root.has("context_delivery")) return report;
    if (design) {
      JsonElement projects = root.remove("projects");
      if (projects != null && projects.isJsonObject()) {
        root.addProperty("project_count", projects.getAsJsonObject().size());
        root.addProperty(
            "project_job_count",
            projects.getAsJsonObject().asMap().values().stream()
                .mapToLong(JsonElement::getAsLong)
                .sum());
      }
      // The retained goal and latest geometry stay complete. Older variants are local evidence.
      JsonObject salvage = child(root, "salvage_existing_proposal");
      if (salvage != null) {
        tail(salvage, "previous_attempts", 1);
        root.remove("retained_proposals");
      } else tail(root, "retained_proposals", 2);
      tail(root, "feedback", 2);
      tail(root, "worker_results", 2);
      tail(root, "previous_designs", 2);
      head(root, "site_failure_examples", 2);
      JsonObject rejections = child(root, "site_rejections");
      if (rejections != null) {
        JsonObject counts = new JsonObject();
        rejections.entrySet().stream()
            .sorted(
                Comparator.<Map.Entry<String, JsonElement>>comparingLong(
                        e -> e.getValue().getAsLong())
                    .reversed())
            .limit(4)
            .forEach(e -> counts.add(e.getKey(), e.getValue()));
        root.add("site_rejections", counts);
        root.addProperty("site_rejection_categories_total", rejections.size());
      }
      tail(root, "goal_dependencies", 2);
    }
    if (worker) {
      tail(root, "recent_results", 3);
      tail(root, "agent_experiences", 2);
      JsonObject failure = child(root, "last_failure_evidence");
      if (failure != null) {
        // Keep the current failure and concrete target/inventory, not the archived voxel dump.
        JsonObject navigation = child(failure, "navigation");
        if (navigation != null) {
          navigation.remove("cells");
          navigation.remove("map");
          navigation.remove("history");
        }
      }
    }
    if (recovery) {
      JsonObject previous = child(root, "previous_live_attempt");
      if (previous != null) {
        previous.remove("context");
        clip(previous, "evidence", 1200);
      }
    }
    root.addProperty(
        "context_delivery",
        "Focused snapshot; full history is kept in local journals. Omitted history is not evidence"
            + " of success or free terrain. CoreAI executes and verifies the retained goal locally;"
            + " model output is a proposal.");
    return root.toString();
  }

  /** Drop supplementary history in order; never slice JSON, geometry, jobs or current goals. */
  public static String reduce(String report) {
    JsonObject root = object(report);
    if (root == null || !root.has("context_delivery")) return report;
    for (String name :
        List.of(
            "feedback",
            "worker_results",
            "previous_designs",
            "retained_proposals",
            "agent_experiences",
            "recent_results",
            "site_rejections",
            "goal_dependencies",
            "shared_facts",
            "material_sources",
            "harvesting_capabilities")) {
      if (root.remove(name) != null) {
        JsonArray omitted = root.getAsJsonArray("budget_omissions");
        if (omitted == null) {
          omitted = new JsonArray();
          root.add("budget_omissions", omitted);
        }
        omitted.add(name);
        return root.toString();
      }
    }
    JsonObject salvage = child(root, "salvage_existing_proposal");
    if (salvage != null && salvage.remove("previous_attempts") != null) return root.toString();
    return report;
  }

  private static void clip(JsonObject object, String name, int length) {
    JsonElement value = object.get(name);
    if (value != null && value.isJsonPrimitive() && value.getAsString().length() > length)
      object.addProperty(
          name, value.getAsString().substring(0, length) + " [full evidence in local journal]");
  }

  private static void head(JsonObject object, String name, int count) {
    limit(object, name, count, false);
  }

  private static void tail(JsonObject object, String name, int count) {
    limit(object, name, count, true);
  }

  private static void limit(JsonObject object, String name, int count, boolean tail) {
    JsonElement value = object.get(name);
    if (value == null || !value.isJsonArray()) return;
    var all = value.getAsJsonArray();
    // Repeated identical failures do not deserve multiple places in the working snapshot.
    var unique = new LinkedHashSet<JsonElement>();
    for (var entry : all) {
      unique.remove(entry);
      unique.add(entry);
    }
    var entries = new ArrayList<>(unique);
    JsonArray chosen = new JsonArray();
    int start = tail ? Math.max(0, entries.size() - count) : 0;
    for (int i = start; i < Math.min(entries.size(), start + count); i++)
      chosen.add(entries.get(i));
    object.add(name, chosen);
    if (chosen.size() < all.size())
      object.addProperty(name + "_omitted", all.size() - chosen.size());
  }

  private static JsonObject child(JsonObject root, String name) {
    JsonElement value = root.get(name);
    return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
  }

  private static JsonObject object(String text) {
    try {
      var value = JsonParser.parseString(text);
      return value.isJsonObject() ? value.getAsJsonObject() : null;
    } catch (JsonParseException | IllegalStateException ignored) {
      return null;
    }
  }
}
