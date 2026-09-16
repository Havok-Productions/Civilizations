package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import java.util.*;
import java.util.function.*;
import org.bukkit.entity.Villager;

/** Admin-requested construction experiments, separate from task execution and its receipts. */
final class WorkerDesignTrial {
  private final CivilizationsPlugin plugin;
  private final Villager entity;
  private final Settlement village;
  private final BiFunction<String, Long, List<String>> start;
  private volatile long generation;
  private volatile boolean pending;
  private Map<String, Object> context = Map.of();

  WorkerDesignTrial(
      CivilizationsPlugin plugin,
      Villager entity,
      Settlement village,
      BiFunction<String, Long, List<String>> start) {
    this.plugin = plugin;
    this.entity = entity;
    this.village = village;
    this.start = start;
  }

  boolean pending() {
    return pending;
  }

  void cancel() {
    generation++;
    pending = false;
  }

  void resetContext() {
    context = Map.of();
  }

  List<String> proposals() {
    var lines = new ArrayList<String>();
    lines.add("Worker " + entity.getUniqueId() + "; retained construction proposals:");
    village.proposals().stream()
        .sorted(Comparator.comparingLong(p -> p.origin().distance2(here())))
        .forEach(
            p ->
                lines.add(
                    p.id()
                        + " | "
                        + p.kind()
                        + " | origin="
                        + p.origin().key()
                        + " | "
                        + p.reason()));
    if (village.proposals().isEmpty()) lines.add("No retained proposals for this village.");
    return lines;
  }

  List<String> request(
      String requested, long duration, boolean active, Consumer<List<String>> reply) {
    if (active || pending)
      return List.of("This worker already has a probe or trial survey running.");
    if (village.retired() || !entity.isValid())
      return List.of("Worker/village is no longer active.");
    var matches =
        village.proposals().stream()
            .filter(p -> requested.equalsIgnoreCase("auto") || p.id().startsWith(requested))
            .sorted(Comparator.comparingLong(p -> p.origin().distance2(here())))
            .toList();
    if (matches.isEmpty())
      return List.of("No matching retained proposal. Use /civ debug probe proposals.");
    if (!requested.equalsIgnoreCase("auto") && matches.size() != 1)
      return List.of("Proposal ID must identify exactly one retained proposal.");
    var proposal = matches.getFirst();
    String worker = entity.getUniqueId().toString();
    long token = ++generation;
    pending = true;
    event(
        "design_trial_requested",
        Map.of(
            "proposal",
            proposal.id(),
            "blueprint",
            proposal.blueprint(),
            "origin",
            proposal.origin(),
            "original_rejection",
            proposal.reason()));
    plugin
        .trialDesign(
            village,
            entity.getWorld(),
            proposal,
            worker,
            here(),
            () -> token == generation && pending)
        .whenComplete(
            (result, error) -> {
              event(
                  "design_trial_admission",
                  error != null
                      ? Map.of(
                          "proposal", proposal.id(), "admitted", false, "reason", error.toString())
                      : Map.of(
                          "proposal",
                          proposal.id(),
                          "admitted",
                          result.admission().accepted(),
                          "warnings",
                          result.warnings(),
                          "reason",
                          result.admission().accepted()
                              ? "Trial jobs queued; execution unproven"
                              : result.admission().proposal().reason(),
                          "jobs",
                          result.admission().design() == null
                              ? 0
                              : result.admission().design().jobs()));
              Runnable unloaded =
                  () ->
                      reply.accept(
                          List.of(
                              "Worker unloaded during trial preparation; see design_trial_admission"
                                  + " in the probe journal."));
              var task =
                  entity
                      .getScheduler()
                      .run(
                          plugin,
                          ignored ->
                              plugin
                                  .connections()
                                  .read(
                                      () -> {
                                        if (generation != token) return;
                                        pending = false;
                                        if (error != null) {
                                          reply.accept(
                                              List.of(
                                                  "No execution trial started: "
                                                      + error.getMessage()));
                                          return;
                                        }
                                        if (!result.admission().accepted()) {
                                          reply.accept(
                                              List.of(
                                                  "No execution trial started: "
                                                      + result.admission().proposal().reason()));
                                          return;
                                        }
                                        String project = result.admission().design().project();
                                        long now = System.currentTimeMillis();
                                        var selected =
                                            village.jobs().stream()
                                                .filter(j -> j.project.equals(project))
                                                .filter(j -> village.available(j.id, worker, now))
                                                .min(
                                                    Comparator.comparingLong(
                                                        j -> here().distance2(j.target)))
                                                .orElse(null);
                                        if (selected == null) {
                                          reply.accept(
                                              List.of(
                                                  "Trial project queued, but no task is currently"
                                                      + " available to this worker; inspect probe"
                                                      + " jobs."));
                                          return;
                                        }
                                        var lines = new ArrayList<String>();
                                        lines.add(
                                            "Trial admitted "
                                                + proposal.kind()
                                                + " "
                                                + proposal.id()
                                                + "; "
                                                + result.admission().design().jobs()
                                                + " real queued actions.");
                                        lines.add("Planning warnings: " + result.warnings());
                                        lines.addAll(start.apply(selected.id, duration));
                                        context =
                                            Map.of(
                                                "proposal",
                                                proposal.id(),
                                                "project",
                                                project,
                                                "original_rejection",
                                                proposal.reason(),
                                                "warnings",
                                                result.warnings(),
                                                "job",
                                                selected.id);
                                        event("design_trial_probe", context);
                                        lines.add(
                                            "Probe PASS verifies its assigned action, not the whole"
                                                + " structure. The project remains queued"
                                                + " afterward.");
                                        reply.accept(lines);
                                      }),
                          unloaded);
              if (task == null) unloaded.run();
            });
    return List.of(
        "Surveying retained " + proposal.kind() + " " + proposal.id() + " for a live trial.",
        "Original rejection: " + proposal.reason());
  }

  void result(Map<String, Object> receipt) {
    if (context.isEmpty() || !context.get("job").equals(receipt.get("job"))) return;
    var evidence = new LinkedHashMap<String, Object>(context);
    evidence.put("assigned_action", receipt);
    evidence.put(
        "completed_project_actions",
        village.jobs().stream()
            .filter(j -> j.project.equals(context.get("project")) && j.complete)
            .count());
    event("design_trial_result", evidence);
  }

  private void event(String type, Map<String, ?> data) {
    plugin.probeEvent(village.id(), entity.getUniqueId().toString(), type, data);
  }

  private Pos here() {
    var at = entity.getLocation();
    return new Pos(at.getBlockX(), at.getBlockY(), at.getBlockZ());
  }
}
