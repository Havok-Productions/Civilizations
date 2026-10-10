package dev.civilizations.playerai;

import com.google.gson.Gson;
import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.Pos;
import dev.civilizations.world.RegionSnapshots;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Core coordinator: NPC lifecycle, command routing, camp reservations and persisted identities. */
public final class PlayerAiCoordinator
    implements Listener, CommandExecutor, TabCompleter, AutoCloseable {
  private final CivilizationsPlugin plugin;
  private final NamespacedKey stateKey;
  private final Gson gson = new Gson();
  private final boolean enabled;
  private final int maximum, radius;
  private final ExecutorService planning =
      Executors.newFixedThreadPool(
          2,
          runnable -> {
            Thread thread = new Thread(runnable, "Civilizations-playerai-planning");
            thread.setDaemon(true);
            return thread;
          });
  private final RegionSnapshots snapshots;
  private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

  private record Camp(UUID world, Pos origin) {}

  private final Map<UUID, Camp> camps = new HashMap<>();
  private volatile boolean closed;

  private static final class Session {
    final Mannequin body;
    final PlayerAi brain;
    final MannequinActions actions;
    volatile PlayerAi.State state;
    volatile String status = "Starting";
    ScheduledTask task;

    Session(Mannequin body, PlayerAi brain, MannequinActions actions, PlayerAi.State state) {
      this.body = body;
      this.brain = brain;
      this.actions = actions;
      this.state = state;
    }
  }

  public PlayerAiCoordinator(CivilizationsPlugin plugin) {
    this.plugin = plugin;
    stateKey = new NamespacedKey(plugin, "playerai_state");
    enabled = plugin.getConfig().getBoolean("player-ai.enabled", true);
    maximum = Math.clamp(plugin.getConfig().getInt("player-ai.max-active", 8), 1, 32);
    radius = Math.clamp(plugin.getConfig().getInt("player-ai.search-radius", 16), 12, 24);
    snapshots = new RegionSnapshots(plugin, planning);
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    var command = Objects.requireNonNull(plugin.getCommand("playerai"));
    command.setExecutor(this);
    command.setTabCompleter(this);
  }

  /** Called on the entity's owning region, including loaded-chunk discovery after restart. */
  public synchronized void attach(Mannequin body) {
    if (closed
        || !enabled
        || !body.isValid()
        || body.isDead()
        || sessions.containsKey(body.getUniqueId())
        || sessions.size() >= maximum) return;
    String json = body.getPersistentDataContainer().get(stateKey, PersistentDataType.STRING);
    if (json == null) return; // Never adopt somebody else's mannequin.
    try {
      PlayerAi.State state = gson.fromJson(json, PlayerAi.State.class);
      if (state == null) throw new IllegalArgumentException("Empty state");
      if (state.camp() != null
          && !reserve(body.getUniqueId(), body.getWorld().getUID(), state.camp())) {
        plugin
            .getLogger()
            .warning(
                "PlayerAi camp conflict for " + body.getUniqueId() + "; saved NPC left inactive");
        return;
      }
      MannequinActions actions =
          new MannequinActions(this, body, snapshots, planning, radius, next -> save(body, next));
      PlayerAi brain = new PlayerAi(state, actions);
      Session session = new Session(body, brain, actions, state);
      sessions.put(body.getUniqueId(), session);
      body.setImmovable(false);
      body.setGravity(true);
      body.setPersistent(true);
      body.setRemoveWhenFarAway(false);
      body.customName(Component.text(state.profile().name()));
      body.setCustomNameVisible(true);
      body.setDescription(Component.text("AI pioneer"));
      session.task =
          body.getScheduler()
              .runAtFixedRate(
                  plugin, task -> tick(session), () -> retire(body.getUniqueId(), session), 1, 2);
      if (session.task == null) retire(body.getUniqueId(), session);
    } catch (RuntimeException error) {
      Session session = sessions.get(body.getUniqueId());
      if (session != null) retire(body.getUniqueId(), session);
      else camps.remove(body.getUniqueId());
      plugin
          .getLogger()
          .warning("Cannot restore PlayerAi " + body.getUniqueId() + ": " + error.getMessage());
    }
  }

  private void tick(Session session) {
    if (closed || sessions.get(session.body.getUniqueId()) != session) return;
    try {
      session.brain.tick(System.currentTimeMillis());
      session.status = session.brain.stage() + ": " + session.brain.detail();
    } catch (RuntimeException error) {
      session.brain.pause(true);
      session.status = "PAUSED after an executor error; inspect the server log";
      plugin
          .getLogger()
          .log(
              java.util.logging.Level.WARNING,
              "PlayerAi " + session.state.profile().id() + " paused",
              error);
    }
  }

  private void save(Mannequin body, PlayerAi.State state) {
    body.getPersistentDataContainer().set(stateKey, PersistentDataType.STRING, gson.toJson(state));
    Session session = sessions.get(body.getUniqueId());
    if (session != null) session.state = state;
  }

  synchronized boolean reserve(UUID entity, UUID world, Pos origin) {
    if (camps.entrySet().stream()
        .anyMatch(
            e ->
                !e.getKey().equals(entity)
                    && e.getValue().world().equals(world)
                    && Math.abs((long) e.getValue().origin().x() - origin.x()) < 6
                    && Math.abs((long) e.getValue().origin().z() - origin.z()) < 6)) return false;
    camps.put(entity, new Camp(world, origin));
    return true;
  }

  private synchronized void retire(UUID id, Session session) {
    if (!sessions.remove(id, session)) return;
    if (session.task != null) session.task.cancel();
    camps.remove(id);
    // Retired callbacks must not access entity/world state. Outstanding snapshots have no
    // mutation callbacks and cannot execute actions after this session is removed.
  }

  @EventHandler
  public void onLoad(EntitiesLoadEvent event) {
    if (closed || !enabled) return;
    for (Entity entity : event.getEntities())
      if (entity instanceof Mannequin body)
        body.getScheduler().run(plugin, task -> attach(body), () -> {});
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPlace(BlockPlaceEvent event) {
    if (!enabled || !PlayerAi.log(event.getBlockPlaced().getType().name())) return;
    // Durable per-block provenance prevents harvesting player-made log structures, including
    // those outside villager settlements. It is intentionally retained after block removal.
    Block block = event.getBlockPlaced();
    block
        .getChunk()
        .getPersistentDataContainer()
        .set(placedKey(block), PersistentDataType.BYTE, (byte) 1);
  }

  private NamespacedKey placedKey(Block block) {
    return new NamespacedKey(
        plugin,
        "playerai_log_" + (block.getX() & 15) + "_" + block.getY() + "_" + (block.getZ() & 15));
  }

  boolean protectedBlock(Block block) {
    return block
            .getChunk()
            .getPersistentDataContainer()
            .has(placedKey(block), PersistentDataType.BYTE)
        || plugin.playerAiProtected(block.getWorld(), PlayerAiNavigation.pos(block.getLocation()));
  }

  @EventHandler
  public void onDeath(EntityDeathEvent event) {
    if (!(event.getEntity() instanceof Mannequin body)) return;
    // Unloaded/over-limit NPCs still own their persisted inventory.
    String json = body.getPersistentDataContainer().get(stateKey, PersistentDataType.STRING);
    if (json == null) return;
    try {
      PlayerAi.State state = gson.fromJson(json, PlayerAi.State.class);
      state
          .inventory()
          .forEach(
              (material, amount) ->
                  event.getDrops().add(new ItemStack(Material.valueOf(material), amount)));
      body.getPersistentDataContainer().remove(stateKey);
    } catch (RuntimeException error) {
      plugin.getLogger().warning("Invalid PlayerAi death inventory: " + error.getMessage());
    }
    Session session = sessions.get(body.getUniqueId());
    if (session != null) retire(body.getUniqueId(), session);
  }

  private synchronized String spawn(Player player, String name) {
    if (sessions.size() >= maximum) return "Active PlayerAi limit reached (" + maximum + ").";
    var profile = PlayerAiProfile.generate(ThreadLocalRandom.current().nextLong(), name);
    // Spawn on the command sender's region, at a nearby empty supported block.
    Location where = null;
    Pos here = PlayerAiNavigation.pos(player.getLocation());
    for (int[] offset : new int[][] {{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
      Pos p = here.add(offset[0], 0, offset[1]);
      Location candidate = new Location(player.getWorld(), p.x() + .5, p.y(), p.z() + .5);
      if (!PlayerAiNavigation.ownedLoaded(player.getWorld(), p, 1)
          || !player.getWorld().getWorldBorder().isInside(candidate)) continue;
      Block feet = candidate.getBlock();
      if (!feet.getType().isAir()
          || !feet.getRelative(0, 1, 0).getType().isAir()
          || !feet.getRelative(0, -1, 0).getType().isOccluding()
          || Set.of(Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE)
              .contains(feet.getRelative(0, -1, 0).getType())
          || !dev.civilizations.world.BlockRules.dry(feet)
          || !player.getWorld().getNearbyEntities(candidate, .6, 1, .6).isEmpty()) continue;
      where = candidate;
      break;
    }
    if (where == null) return "No safe spawn space nearby. Stand on dry, open ground.";
    Mannequin body =
        player.getWorld().spawn(where, Mannequin.class, npc -> save(npc, PlayerAi.fresh(profile)));
    attach(body);
    if (!sessions.containsKey(body.getUniqueId())) {
      body.remove();
      return "NPC spawn was cancelled or initialization failed; check the server log.";
    }
    return "Spawned "
        + profile.name()
        + " ("
        + profile.id()
        + "). Goal: gather wood and build a starter canopy with a workbench.";
  }

  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!sender.hasPermission("civilizations.playerai.admin")) {
      sender.sendMessage("You do not have permission to manage AI players.");
      return true;
    }
    if (!enabled || closed) {
      sender.sendMessage("PlayerAi is disabled in config.yml.");
      return true;
    }
    String action = args.length == 0 ? "spawn" : args[0].toLowerCase(Locale.ROOT);
    if (action.equals("spawn") && args.length <= 2) {
      if (!(sender instanceof Player player)) {
        sender.sendMessage("Run /playerai in-game to choose a spawn location.");
        return true;
      }
      // Explicitly marshal commands to the player's entity scheduler for Folia.
      player
          .getScheduler()
          .run(
              plugin,
              task -> {
                if (closed) return;
                try {
                  player.sendMessage(spawn(player, args.length == 2 ? args[1] : null));
                } catch (IllegalArgumentException error) {
                  player.sendMessage(error.getMessage());
                }
              },
              () -> {});
      return true;
    }
    if ((action.equals("list") || action.equals("status")) && args.length == 1) {
      sender.sendMessage("Loaded AI players: " + sessions.size() + "/" + maximum);
      sessions.values().stream()
          .sorted(Comparator.comparing(s -> s.state.profile().name()))
          .forEach(
              s ->
                  sender.sendMessage(
                      s.state.profile().name()
                          + " ["
                          + s.state.profile().id()
                          + "] "
                          + s.status
                          + " | built="
                          + s.state.built()
                          + "/18 | inventory="
                          + s.state.inventory()));
      return true;
    }
    if (Set.of("status", "pause", "resume", "remove").contains(action) && args.length == 2) {
      List<Session> matches =
          sessions.values().stream()
              .filter(
                  s ->
                      s.state.profile().id().toString().equalsIgnoreCase(args[1])
                          || s.state.profile().name().equalsIgnoreCase(args[1]))
              .toList();
      if (matches.size() != 1) {
        sender.sendMessage("Expected one loaded AI player; use /playerai list and its full UUID.");
        return true;
      }
      Session session = matches.getFirst();
      if (action.equals("status")) {
        sender.sendMessage(
            session.state.profile().name() + ": " + session.status + " | " + session.state);
        return true;
      }
      var scheduled =
          session
              .body
              .getScheduler()
              .run(
                  plugin,
                  task -> {
                    if (closed || sessions.get(session.body.getUniqueId()) != session) return;
                    if (action.equals("remove")) {
                      session.actions.halt();
                      session
                          .state
                          .inventory()
                          .forEach(
                              (material, amount) ->
                                  session
                                      .body
                                      .getWorld()
                                      .dropItemNaturally(
                                          session.body.getLocation(),
                                          new ItemStack(Material.valueOf(material), amount)));
                      session.body.getPersistentDataContainer().remove(stateKey);
                      retire(session.body.getUniqueId(), session);
                      session.body.remove();
                    } else {
                      session.brain.pause(action.equals("pause"));
                      session.status =
                          action.equals("pause") ? "PAUSED by administrator" : "Resuming";
                    }
                    reply(sender, action + " completed for " + session.state.profile().name());
                  },
                  () -> reply(sender, "NPC unloaded before the command could run."));
      if (scheduled == null) reply(sender, "NPC is no longer loaded.");
      return true;
    }
    sender.sendMessage(
        "/playerai [spawn [name]|list|status [name/UUID]|pause <name/UUID>|resume"
            + " <name/UUID>|remove <name/UUID>]");
    return true;
  }

  private void reply(CommandSender sender, String message) {
    if (closed) return;
    if (sender instanceof Player player)
      player.getScheduler().run(plugin, task -> player.sendMessage(message), () -> {});
    else sender.sendMessage(message);
  }

  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    if (!sender.hasPermission("civilizations.playerai.admin")) return List.of();
    List<String> choices =
        args.length == 1
            ? List.of("spawn", "list", "status", "pause", "resume", "remove")
            : args.length == 2 && Set.of("status", "pause", "resume", "remove").contains(args[0])
                ? sessions.values().stream()
                    .map(s -> s.state.profile().name())
                    .distinct()
                    .sorted()
                    .toList()
                : List.of();
    String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
    return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
  }

  public synchronized void close() {
    closed = true;
    sessions
        .values()
        .forEach(
            s -> {
              if (s.task != null) s.task.cancel();
            });
    sessions.clear();
    camps.clear();
    planning.shutdownNow();
    // Entity PDC is saved by Minecraft with the body. Never recreate bodies on startup.
  }
}
