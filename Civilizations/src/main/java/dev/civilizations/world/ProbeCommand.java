package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

/** Small admin adapter; all worker reads and assignments run on the entity scheduler. */
public final class ProbeCommand {
  private ProbeCommand() {}

  public static void execute(CivilizationsPlugin plugin, CommandSender sender, String[] args) {
    String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "start";
    if (!Set.of("start", "status", "cancel", "jobs").contains(action) || args.length > 5) {
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
      if (!Bukkit.isOwnedByCurrentRegion(player.getLocation(), 1)) {
        sender.sendMessage("Nearby region is unavailable; retry or specify a worker UUID.");
        return;
      }
      var actor =
          player.getNearbyEntities(16, 8, 16).stream()
              .filter(e -> e instanceof Villager && Bukkit.isOwnedByCurrentRegion(e))
              .filter(e -> plugin.worker(e.getUniqueId().toString()) != null)
              .min(
                  Comparator.comparingDouble(
                      e -> e.getLocation().distanceSquared(player.getLocation())))
              .orElse(null);
      if (actor == null) {
        sender.sendMessage("No controlled villager within 16 blocks.");
        return;
      }
      workerId = actor.getUniqueId().toString();
    }
    var worker = plugin.worker(workerId);
    if (worker == null) {
      sender.sendMessage("No loaded controlled worker with UUID " + workerId);
      return;
    }
    worker.probe(
        action,
        args.length > 3 ? args[3] : "auto",
        duration,
        lines -> {
          if (sender instanceof Player player)
            player.getScheduler().run(plugin, t -> lines.forEach(player::sendMessage), () -> {});
          else lines.forEach(sender::sendMessage);
        });
  }

  private static void usage(CommandSender sender) {
    sender.sendMessage(
        "/civ debug probe [start|status|cancel|jobs] [nearest|worker-uuid] [job-id|auto]"
            + " [seconds]");
    sender.sendMessage(
        "Runs an existing task with actual resources. Default: nearest worker, current/nearest"
            + " available task, 120 seconds.");
  }
}
