package test;

import dev.civilizations.*;
import dev.civilizations.core.*;
import dev.civilizations.world.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.Chest.Type;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Targeted real-world tests of observation and inventory actions; no model or game smoke test. */
final class ObservationChecks implements Listener {
  private final JavaPlugin fixture;
  private CivilizationsPlugin plugin;
  private Settlement village;
  private Villager actor,peer;
  private boolean cancel;
  private int base;
  ObservationChecks(JavaPlugin fixture) { this.fixture=fixture; }
  @EventHandler public void pickup(EntityPickupItemEvent event) { if(cancel) event.setCancelled(true); }
  void start() {
    Bukkit.getPluginManager().registerEvents(this,fixture);
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture,t -> prepare(),60);
  }
  void prepare() {
    plugin=(CivilizationsPlugin)Bukkit.getPluginManager().getPlugin("Civilizations");
    World w=Bukkit.getWorlds().getFirst();w.setTime(1000);
    List<CompletableFuture<Void>> waits=new ArrayList<>();
    for(int x=-3;x<=3;x++) for(int z=-3;z<=3;z++) {
      int cx=x,cz=z;CompletableFuture<Void> f=new CompletableFuture<>();waits.add(f);
      w.getChunkAtAsync(cx,cz,true).thenAccept(c -> Bukkit.getRegionScheduler().execute(fixture,w,cx,cz,() -> { c.addPluginChunkTicket(fixture);f.complete(null); }));
    }
    CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new)).thenRun(() ->
      Bukkit.getRegionScheduler().runDelayed(fixture,new Location(w,0,1,0),t -> seed(w),40));
  }
  @SuppressWarnings("unchecked") void seed(World w) {
    try {
      base=w.getHighestBlockYAt(0,0)+1;
      // A double chest straddles a chunk boundary; coal exists only in the other half.
      for(int x: new int[]{15,16}) {
        var data=(org.bukkit.block.data.type.Chest)Material.CHEST.createBlockData();
        data.setFacing(BlockFace.NORTH);data.setType(x==15?Type.LEFT:Type.RIGHT);
        w.getBlockAt(x,base,0).setBlockData(data,false);
      }
      Chest far=(Chest)w.getBlockAt(16,base,0).getState();far.getBlockInventory().addItem(new ItemStack(Material.COAL,4));
      Chest near=(Chest)w.getBlockAt(15,base,0).getState();
      if(near.getInventory().getSize()!=54) throw new AssertionError("Fixture double chest not joined");
      Settlement.Data d=new Settlement.Data();d.world=w.getUID().toString();d.center=new Pos(0,base,0);d.radius=12;d.chest=new Pos(15,base,0);village=new Settlement(d);
      Field settlements=CivilizationsPlugin.class.getDeclaredField("settlements");settlements.setAccessible(true);
      ((Map<String,Settlement>)settlements.get(plugin)).put(village.id(),village);
      actor=w.spawn(new Location(w,.5,base,.5),Villager.class);actor.setAdult();actor.setAI(false);attach(actor);
      peer=w.spawn(new Location(w,1.5,base,.5),Villager.class);peer.setAdult();peer.setAI(false);attach(peer);
      village.knowledge().block("resource:COAL","No source",System.currentTimeMillis(),60_000);
      new StorageObserver(plugin).refresh(village,w);
      actor.getScheduler().runDelayed(fixture,t -> verify(w),() -> {},20);
    } catch(Throwable e) { fail(e); }
  }
  void attach(Villager v) throws Exception {
    Method m=CivilizationsPlugin.class.getDeclaredMethod("attach",Villager.class,Settlement.class);m.setAccessible(true);m.invoke(plugin,v,village);
    plugin.worker(v.getUniqueId().toString()).stop();
  }
  void verify(World w) {
    try {
      if(village.stock().getOrDefault("COAL",0)!=4 || village.stockAge(System.currentTimeMillis())>5000) throw new AssertionError("Distant double chest not freshly observed");
      if(village.knowledge().blocked("resource:COAL",System.currentTimeMillis())) throw new AssertionError("Stale missing-coal fact survived real stock observation");
      Item gift=w.dropItem(actor.getLocation(),new ItemStack(Material.COAL,20));gift.setThrower(UUID.randomUUID());gift.setPickupDelay(0);
      new NearbyWork(plugin,actor,village).tick(Map.of(),System.currentTimeMillis());
      if(InventoryOps.count(actor.getInventory(),Material.COAL)!=16 || gift.getItemStack().getAmount()!=4) throw new AssertionError("Useful player gift not accepted within bound");
      actor.getInventory().clear();
      Item denied=w.dropItem(actor.getLocation(),new ItemStack(Material.BIRCH_LOG,2));denied.setThrower(UUID.randomUUID());denied.setPickupDelay(0);
      cancel=true;new NearbyWork(plugin,actor,village).tick(Map.of("LOG",1),System.currentTimeMillis());cancel=false;
      if(InventoryOps.total(actor.getInventory())!=0 || denied.getItemStack().getAmount()!=2) throw new AssertionError("Cancelled pickup changed inventory");
      denied.remove();gift.remove();
      peer.getInventory().addItem(new ItemStack(Material.COAL,2));
      new NearbyWork(plugin,actor,village).tick(Map.of("COAL",1),System.currentTimeMillis());
      if(InventoryOps.count(peer.getInventory(),Material.COAL)!=1 || InventoryOps.count(actor.getInventory(),Material.COAL)!=1) throw new AssertionError("Receipt did not correspond to real handoff");
      actor.getInventory().clear();
      Item deliveryGift=w.dropItem(actor.getLocation(),new ItemStack(Material.BIRCH_LOG,3));deliveryGift.setThrower(UUID.randomUUID());deliveryGift.setPickupDelay(0);
      new NearbyWork(plugin,actor,village).tick(Map.of(),System.currentTimeMillis());
      if(deliveryGift.isValid() || InventoryOps.count(actor.getInventory(),Material.BIRCH_LOG)!=3) throw new AssertionError("Delivery gift not collected");
      // Enable the ordinary worker to complete the idle donation-delivery task without an AI call.
      Field workers=CivilizationsPlugin.class.getDeclaredField("workers");workers.setAccessible(true);
      ((Map<String,VillagerWorker>)workers.get(plugin)).remove(actor.getUniqueId().toString());
      actor.setAI(true);attachRunning(actor);long start=System.currentTimeMillis();
      fixture.getLogger().info("OBSERVATION PASS: remote double chest/other half, fresh coal fact, useful player gift, cancelled pickup, real handoff");
      actor.getScheduler().runAtFixedRate(fixture,t -> {
        try {
          if(village.stock().getOrDefault("BIRCH_LOG",0)==3 && InventoryOps.count(actor.getInventory(),Material.BIRCH_LOG)==0) {
            fixture.getLogger().info("DELIVERY PASS: idle worker physically stored useful gift; no unfinished task bypass; AI="+plugin.inference().status());
            t.cancel();Bukkit.getGlobalRegionScheduler().runDelayed(fixture,x -> Bukkit.shutdown(),20);
          } else if(System.currentTimeMillis()-start>90_000) { t.cancel();fail(new AssertionError("Gift delivery deadline")); }
        } catch(Throwable e) { t.cancel();fail(e); }
      },() -> {},20,20);
    } catch(Throwable e) { fail(e); }
  }
  void attachRunning(Villager v) throws Exception {
    Method m=CivilizationsPlugin.class.getDeclaredMethod("attach",Villager.class,Settlement.class);m.setAccessible(true);m.invoke(plugin,v,village);
  }
  void fail(Throwable e) {
    fixture.getLogger().severe("OBSERVATION FAIL: "+e);e.printStackTrace();
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture,t -> Bukkit.shutdown(),20);
  }
}
