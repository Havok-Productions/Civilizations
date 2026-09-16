package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.WorkerPosition;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Small admin adapter; all worker reads and assignments run on the entity scheduler. */
public final class ProbeCommand {
  private ProbeCommand() {}

  public static void execute(CivilizationsPlugin plugin, CommandSender sender, String[] args) {
    String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "start";
    if (!Set.of("start", "trial", "proposals", "status", "cancel", "jobs").contains(action)
        || args.length > 5) {
      usage(sender);
      return;
    }
    long duration = 120_000;
    try {
      if (args.length > 4) duration = Math.multiplyExact(Long.parseLong(args[4]), 1000);
      if (duration <= 0) throw new IllegalArgumentException();
      Math.addExact(System.currentTimeMillis(), duration);
    } catch (IllegalArgumentException | ArithmeticException error) {
      sender.sendMessage("Seconds must be a positive whole number that fits the probe clock.");
      return;
    }
    String workerId = args.length > 2 ? args[2] : "nearest";
    if (workerId.equalsIgnoreCase("nearest")) {
      if (!(sender instanceof Player player)) {
        sender.sendMessage("Console: specify a worker UUID from /civ debug details.");
        usage(sender);
        return;
      }
      long window = duration;
      player.sendMessage("Locating the nearest loaded controlled villager...");
      positions(plugin)
          .thenAccept(
              samples ->
                  player
                      .getScheduler()
                      .run(
                          plugin,
                          ignored -> {
                            var at = player.getLocation();
                            var origin =
                                new WorkerPosition(
                                    "", at.getWorld().getUID(), at.getX(), at.getY(), at.getZ());
                            var selected =
                                WorkerPosition.nearest(origin, samples.positions()).orElse(null);
                            if (samples.unavailable() > 0)
                              player.sendMessage(
                                  samples.unavailable()
                                      + " workers unloaded or did not respond; choosing among"
                                      + " responding workers.");
                            if (selected == null) {
                              refuse(
                                  plugin,
                                  player,
                                  "No loaded controlled villager responded in your current world."
                                      + " No trial started.");
                              return;
                            }
                            double distance = Math.sqrt(origin.distanceSquared(selected));
                            player.sendMessage(
                                "Selected villager "
                                    + selected.worker()
                                    + " ("
                                    + String.format(Locale.ROOT, "%.1f", distance)
                                    + " blocks away).");
                            plugin.probeEvent(
                                "",
                                selected.worker(),
                                "probe_selected",
                                Map.of(
                                    "selection",
                                    "nearest",
                                    "action",
                                    action,
                                    "position",
                                    selected,
                                    "distance_blocks",
                                    distance,
                                    "unavailable_workers",
                                    samples.unavailable()));
                            run(
                                plugin,
                                player,
                                selected.worker(),
                                action,
                                args.length > 3 ? args[3] : "auto",
                                window);
                          },
                          () -> {}));
      return;
    }
    run(plugin, sender, workerId, action, args.length > 3 ? args[3] : "auto", duration);
  }

  public record Positions(List<WorkerPosition> positions, int unavailable) {}

  /** Query each actor through its own scheduler; no range cutoff or neighbouring-region reads. */
  public static CompletableFuture<Positions> positions(CivilizationsPlugin plugin) {
    var calls = plugin.workers().stream().map(VillagerWorker::probePosition).toList();
    return CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new))
        .thenApply(
            ignored -> {
              var found = calls.stream().map(f -> f.getNow(null)).filter(Objects::nonNull).toList();
              return new Positions(found, calls.size() - found.size());
            });
  }

  private static void run(
      CivilizationsPlugin plugin,
      CommandSender sender,
      String workerId,
      String action,
      String jobId,
      long duration) {
    var worker = plugin.worker(workerId);
    if (worker == null) {
      refuse(plugin, sender, "No loaded controlled worker with UUID " + workerId);
      return;
    }
    worker.probe(
        action,
        jobId,
        duration,
        lines -> {
          if (sender instanceof Player player)
            player.getScheduler().run(plugin, t -> lines.forEach(player::sendMessage), () -> {});
          else lines.forEach(sender::sendMessage);
        });
  }

  private static void refuse(CivilizationsPlugin plugin, CommandSender sender, String reason) {
    plugin.probeEvent("", "", "probe_not_started", Map.of("reason", reason));
    sender.sendMessage(reason);
  }

  private static void usage(CommandSender sender) {
    sender.sendMessage(
        "/civ debug probe [start|trial|proposals|status|cancel|jobs] [nearest|worker-uuid]"
            + " [job-or-proposal-id|auto] [seconds]");
    sender.sendMessage(
        "Runs an existing task with actual resources. Default: nearest loaded controlled worker in"
            + " your world, current/nearest available task, 120 seconds.");
    sender.sendMessage(
        "trial: attempt a retained construction proposal despite need/purpose/access predictions;"
            + " proposals: list their rejection reasons.");
  }
}
