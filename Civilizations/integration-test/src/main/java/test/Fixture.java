package test;

import dev.civilizations.*;
import dev.civilizations.core.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;

public class Fixture extends JavaPlugin {
  private Settlement village;
  private List<Villager> villagers = new ArrayList<>();

  public void onEnable() {
    Scenario scenario;
    try {
      scenario = Scenario.selected();
    } catch (IllegalArgumentException error) {
      getLogger().warning(error.getMessage());
      Bukkit.getPluginManager().disablePlugin(this);
      return;
    }
    switch (scenario) {
      case COOPERATION -> {
        new CooperationChecks(this).start();
        return;
      }
      case PROGRESS -> {
        new ProgressChecks(this).start();
        return;
      }
      case RULE_LEARNING -> {
        new RuleLearningChecks(this).start();
        return;
      }
      case LIVE_SKILL -> {
        new LiveSkillChecks(this).start();
        return;
      }
      case NAVIGATION, REPAIR, AUTONOMY, INFERENCE_BUSY -> {
        if (scenario == Scenario.AUTONOMY && Boolean.getBoolean("civilizations.test.task-probe"))
          new ProbeChecks(this).start();
        else if (scenario == Scenario.AUTONOMY
            && Boolean.getBoolean("civilizations.test.progress-learning"))
          new ProgressLearningChecks(this).start();
        else if (scenario == Scenario.AUTONOMY
            && Boolean.getBoolean("civilizations.test.construction-guards"))
          new ConstructionGuardChecks(this).start();
        else if (scenario == Scenario.NAVIGATION
            && Boolean.getBoolean("civilizations.test.movement-recovery"))
          new NavigationRecoveryChecks(this).start();
        else if (scenario == Scenario.NAVIGATION
            && Boolean.getBoolean("civilizations.test.dynamic-route"))
          new DynamicNavigationChecks(this).start();
        else new StallChecks(this, scenario).start();
        return;
      }
      case CRAFTING -> {
        if (Boolean.getBoolean("civilizations.test.blocker-recovery"))
          new BlockerRecoveryChecks(this).start();
        else new StationChecks(this).start();
        return;
      }
      case CONNECTIONS -> {
        new ConnectionChecks(this).start();
        return;
      }
      case OBSERVATIONS -> {
        if (Boolean.getBoolean("civilizations.test.source-recovery"))
          new SourceRecoveryChecks(this).start();
        else new ObservationChecks(this).start();
        return;
      }
      case WORKFLOW -> {
        new WorkflowChecks(this).start();
        return;
      }
      case DISCOVERY -> {
        new DiscoveryChecks(this).start();
        return;
      }
      case MANUAL_VILLAGE -> {}
    }
    Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> prepare(), 60);
  }

  public boolean onCommand(
      org.bukkit.command.CommandSender sender,
      org.bukkit.command.Command cmd,
      String label,
      String[] args) {
    if (village == null || args.length == 0) return true;
    World world = Bukkit.getWorld(UUID.fromString(village.world()));
    if (args[0].equals("night"))
      Bukkit.getGlobalRegionScheduler().execute(this, () -> world.setTime(13000));
    if (args[0].equals("day"))
      Bukkit.getGlobalRegionScheduler().execute(this, () -> world.setTime(1000));
    if (args[0].equals("inspect"))
      Bukkit.getRegionScheduler()
          .execute(
              this,
              new Location(world, 0, 1, 0),
              () -> {
                int sleeping = 0, active = 0, valid = 0, wrong = 0;
                for (Entity e : world.getNearbyEntities(new Location(world, 0, 1, 0), 48, 32, 48))
                  if (e instanceof Villager v
                      && village.members().contains(e.getUniqueId().toString())) {
                    active++;
                    if (v.isSleeping()) sleeping++;
                    getLogger()
                        .info(
                            "FIXTURE VILLAGER pos="
                                + v.getLocation().toVector()
                                + " sleeping="
                                + v.isSleeping());
                  }
                for (Job j : village.jobs())
                  if (j.complete && j.kind == Job.Kind.PLACE) {
                    if (world
                        .getBlockAt(j.target.x(), j.target.y(), j.target.z())
                        .getType()
                        .name()
                        .equals(j.material)) valid++;
                    else wrong++;
                  }
                getLogger()
                    .info(
                        "FIXTURE INSPECT active="
                            + active
                            + " sleeping="
                            + sleeping
                            + " valid-placed-blocks="
                            + valid
                            + " mismatches="
                            + wrong);
              });
    if (args[0].equals("damage")) {
      Job j =
          village.jobs().stream()
              .filter(
                  k -> k.complete && k.kind == Job.Kind.PLACE && k.material.equals("COBBLESTONE"))
              .findFirst()
              .orElse(null);
      if (j != null)
        Bukkit.getRegionScheduler()
            .execute(
                this,
                new Location(world, j.target.x(), j.target.y(), j.target.z()),
                () -> {
                  world
                      .getBlockAt(j.target.x(), j.target.y(), j.target.z())
                      .setType(Material.AIR, false);
                  getLogger().info("FIXTURE DAMAGED recorded wall block " + j.target);
                });
    }
    return true;
  }

  private void prepare() {
    World world = Bukkit.getWorlds().getFirst();
    world.setTime(1000);
    List<CompletableFuture<Void>> pending = new ArrayList<>();
    for (int x = -3; x <= 3; x++)
      for (int z = -3; z <= 3; z++) {
        int cx = x, cz = z;
        CompletableFuture<Void> f = new CompletableFuture<>();
        pending.add(f);
        world
            .getChunkAtAsync(cx, cz, true)
            .thenAccept(
                chunk ->
                    Bukkit.getRegionScheduler()
                        .execute(
                            this,
                            world,
                            cx,
                            cz,
                            () -> {
                              chunk.addPluginChunkTicket(this);
                              f.complete(null);
                            }))
            .exceptionally(
                e -> {
                  f.completeExceptionally(e);
                  return null;
                });
      }
    CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
        .thenRun(
            () ->
                Bukkit.getRegionScheduler()
                    .execute(this, new Location(world, 0, 65, 0), () -> seed(world)))
        .exceptionally(
            e -> {
              getLogger().severe("FIXTURE FAILED: " + e);
              return null;
            });
  }

  private void seed(World world) {
    try {
      CivilizationsPlugin plugin =
          (CivilizationsPlugin) Bukkit.getPluginManager().getPlugin("Civilizations");
      Method create = CivilizationsPlugin.class.getDeclaredMethod("create", World.class, Pos.class);
      create.setAccessible(true);
      village = (Settlement) create.invoke(plugin, world, new Pos(0, 1, 0));
      boolean fresh = village.population() == 0;
      if (!fresh) {
        for (Entity e : world.getNearbyEntities(new Location(world, 0, 1, 0), 48, 32, 48))
          if (e instanceof Villager && !village.members().contains(e.getUniqueId().toString()))
            e.remove();
      }
      for (int x = -5; x <= 3; x += 2) {
        Block b = world.getBlockAt(x, 1, -3);
        ChunkSnapshot snap = b.getChunk().getChunkSnapshot(true, false, false);
        getLogger()
            .info(
                "FIXTURE BED x="
                    + x
                    + " actual="
                    + b.getBlockData().getAsString()
                    + " snapshot="
                    + snap.getBlockData(x & 15, 1, (-3) & 15).getAsString()
                    + " height="
                    + snap.getHighestBlockYAt(x & 15, (-3) & 15));
      }
      if (fresh) {
        int base = world.getHighestBlockYAt(0, 0) + 1;
        for (int x = -5; x <= 3; x += 2) {
          Block foot = world.getBlockAt(x, base, -3), head = world.getBlockAt(x, base, -4);
          Bed bed = (Bed) Material.WHITE_BED.createBlockData();
          bed.setFacing(BlockFace.NORTH);
          bed.setPart(Bed.Part.FOOT);
          foot.setBlockData(bed, false);
          Bed h = (Bed) bed.clone();
          h.setPart(Bed.Part.HEAD);
          head.setBlockData(h, false);
        }
        Block chest = world.getBlockAt(1, base, 1);
        chest.setType(Material.CHEST, false);
        ((Chest) chest.getState())
            .getBlockInventory()
            .addItem(
                new org.bukkit.inventory.ItemStack(Material.OAK_LOG, 32),
                new org.bukkit.inventory.ItemStack(Material.COAL, 16),
                new org.bukkit.inventory.ItemStack(Material.WHITE_WOOL, 6));
        for (int y = base; y <= base + 2; y++)
          world.getBlockAt(20, y, 0).setType(Material.OAK_LOG, false);
        for (int x = 18; x <= 22; x++)
          for (int z = -2; z <= 2; z++)
            world.getBlockAt(x, base + 3, z).setType(Material.OAK_LEAVES, false);
        Method attach =
            CivilizationsPlugin.class.getDeclaredMethod("attach", Villager.class, Settlement.class);
        attach.setAccessible(true);
        for (int i = 0; i < 5; i++) {
          Villager v = world.spawn(new Location(world, i, base, 3), Villager.class);
          v.setAdult();
          v.setPersistent(true);
          villagers.add(v);
          attach.invoke(plugin, v, village);
        }
      }
      org.bukkit.inventory.Inventory supply = Bukkit.createInventory(null, 9),
          worker = Bukkit.createInventory(null, 9);
      supply.addItem(
          new org.bukkit.inventory.ItemStack(Material.OAK_LOG, 32),
          new org.bukkit.inventory.ItemStack(Material.COAL, 16));
      dev.civilizations.world.InventoryOps.withdrawRecipe(supply, worker, Material.TORCH);
      if (dev.civilizations.world.InventoryOps.count(worker, Material.OAK_LOG) != 1
          || dev.civilizations.world.InventoryOps.count(worker, Material.COAL) != 1)
        throw new AssertionError("Recipe must withdraw both exact ingredients");
      dev.civilizations.world.InventoryOps.consumeRecipe(
          worker,
          Material.TORCH,
          dev.civilizations.world.InventoryOps.cost(Material.TORCH, worker),
          item -> {
            throw new AssertionError("Unexpected overflow");
          });
      if (dev.civilizations.world.InventoryOps.count(worker, Material.TORCH) != 3
          || dev.civilizations.world.InventoryOps.count(supply, Material.COAL) != 15)
        throw new AssertionError("Craft conservation failed");
      getLogger()
          .info(
              "FIXTURE PASS: exact ingredient withdrawal and crafting conservation; restart="
                  + !fresh);
      getLogger().info("FIXTURE SEEDED: " + village.id());
      Bukkit.getGlobalRegionScheduler()
          .runAtFixedRate(
              this,
              t -> {
                long complete = village.jobs().stream().filter(j -> j.complete).count();
                long memories =
                    village.snapshot().agents.values().stream().mapToLong(a -> a.completed).sum();
                getLogger()
                    .info(
                        "FIXTURE PROGRESS jobs="
                            + complete
                            + "/"
                            + village.jobs().size()
                            + " confirmed-actions="
                            + memories
                            + " AI="
                            + plugin.inference().status());
                Bukkit.getRegionScheduler()
                    .execute(
                        this,
                        new Location(world, 0, 1, 0),
                        () -> {
                          Job next =
                              village.jobs().stream()
                                  .filter(j -> j.kind == Job.Kind.MINE && !j.complete)
                                  .findFirst()
                                  .orElse(null);
                          if (next != null)
                            getLogger()
                                .info(
                                    "FIXTURE NEXT MINE "
                                        + next.target
                                        + " expected="
                                        + next.expected
                                        + " actual="
                                        + world
                                            .getBlockAt(
                                                next.target.x(), next.target.y(), next.target.z())
                                            .getType());
                        });
              },
              200,
              400);
    } catch (Exception e) {
      getLogger().severe("FIXTURE FAILED: " + e);
      e.printStackTrace();
    }
  }
}
