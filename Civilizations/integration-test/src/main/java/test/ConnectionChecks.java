package test;

import dev.civilizations.CivilizationsPlugin;
import dev.civilizations.core.*;
import dev.civilizations.world.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Targeted live Folia check: a new proximity link joins two existing worker groups. */
final class ConnectionChecks {
  private final JavaPlugin fixture;
  private CivilizationsPlugin plugin;
  private World world;
  private Villager actor,remote,bridge;
  private int y;
  private String aId,bId;
  ConnectionChecks(JavaPlugin fixture) {this.fixture=fixture;}
  void start() {Bukkit.getGlobalRegionScheduler().runDelayed(fixture,t -> prepare(),60);}
  void prepare() {
    plugin=(CivilizationsPlugin)Bukkit.getPluginManager().getPlugin("Civilizations");
    world=Bukkit.getWorlds().getFirst();world.setTime(1000);
    List<CompletableFuture<Void>> waits=new ArrayList<>();
    for(int x=-3;x<=8;x++) for(int z=-3;z<=3;z++) {
      int cx=x,cz=z;CompletableFuture<Void> f=new CompletableFuture<>();waits.add(f);
      world.getChunkAtAsync(cx,cz,true).thenAccept(c -> Bukkit.getRegionScheduler().execute(fixture,world,cx,cz,()->{c.addPluginChunkTicket(fixture);f.complete(null);}));
    }
    CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new)).thenRun(() ->
      Bukkit.getRegionScheduler().runDelayed(fixture,new Location(world,0,1,0),t -> seed(),60));
  }
  @SuppressWarnings("unchecked") Map<String,Settlement> villages() throws Exception {
    Field f=CivilizationsPlugin.class.getDeclaredField("settlements");f.setAccessible(true);return (Map<String,Settlement>)f.get(plugin);
  }
  Villager spawn(int x) {
    Villager v=world.spawn(new Location(world,x+.5,y,.5),Villager.class);v.setAdult();v.setAI(false);return v;
  }
  void discover(Villager v) throws Exception {
    Method f=CivilizationsPlugin.class.getDeclaredMethod("discover",Villager.class);f.setAccessible(true);f.invoke(plugin,v);
  }
  void seed() {
    try {
      if(!Bukkit.isOwnedByCurrentRegion(new Location(world,80,1,0),1)) throw new AssertionError("Fixture region not joined");
      y=world.getHighestBlockYAt(0,0)+1;
      Settlement.Data a=new Settlement.Data(),b=new Settlement.Data();
      a.world=b.world=world.getUID().toString();a.center=new Pos(0,y,0);b.center=new Pos(80,y,0);a.radius=b.radius=12;
      a.chest=new Pos(1,y,2);b.chest=new Pos(3,y,0);a.paused=b.paused=true;
      for(Pos p:List.of(a.chest,b.chest)) world.getBlockAt(p.x(),p.y(),p.z()).setType(Material.CHEST,false);
      ((Chest)world.getBlockAt(b.chest.x(),y,b.chest.z()).getState()).getInventory().addItem(new ItemStack(Material.COAL,4));
      Settlement av=new Settlement(a),bv=new Settlement(b);aId=av.id();bId=bv.id();
      villages().put(av.id(),av);villages().put(bv.id(),bv);
      actor=spawn(0);remote=spawn(80);discover(actor);discover(remote);
      if(villages().size()!=2) throw new AssertionError("Distant groups joined without a proximity link");
      bridge=spawn(40);discover(bridge);
      if(villages().size()!=1) throw new AssertionError("Bridging villager did not combine groups");
      fixture.getLogger().info("CONNECTION MERGE: two groups 80 blocks apart joined through villager at 40");
      Bukkit.getRegionScheduler().runDelayed(fixture,actor.getLocation(),t->verify(),130);
    }catch(Throwable e){fail(e);}
  }
  void verify() {
    try {
      Settlement v=villages().values().iterator().next();
      if(v.population()!=3 || v.chests().size()!=2 || !v.absorbedIds().contains(v.id().equals(aId)?bId:aId)) throw new AssertionError("Merge lost membership/storage/alias");
      for(Villager entity:List.of(actor,remote,bridge)) {
        VillagerWorker worker=plugin.worker(entity.getUniqueId().toString());
        if(worker==null || worker.retiredVillage() || !worker.villageId().equals(v.id())) throw new AssertionError("Worker did not reconnect to shared village");
      }
      if(v.stock().getOrDefault("COAL",0)!=4) throw new AssertionError("Shared storage stock missing");
      Pos at=new Pos(0,y,0);
      actor.getInventory().addItem(new ItemStack(Material.STICK,1));
      boolean obtained=ChestSupplies.obtain(plugin,actor,v,new WorkerNavigation(plugin,actor,v,(n,msg)->{throw new AssertionError(msg);}),"TORCH",Map.of("STICK",1),false,at,System.currentTimeMillis());
      int carried=InventoryOps.count(actor.getInventory(),Material.COAL);
      int remaining=InventoryOps.count(((Chest)world.getBlockAt(3,y,0).getState()).getInventory(),Material.COAL);
      if(!obtained || carried!=1 || remaining!=3) throw new AssertionError("No physical withdrawal from other former group's chest: "+carried+"/"+remaining);
      fixture.getLogger().info("CONNECTION PASS: 3 workers reattached; 2 shared chests; real coal withdrawal chest 4 -> 3, villager 0 -> 1; AI="+plugin.inference().status());
      Bukkit.getGlobalRegionScheduler().runDelayed(fixture,t->Bukkit.shutdown(),20);
    }catch(Throwable e){fail(e);}
  }
  void fail(Throwable e) {
    fixture.getLogger().severe("CONNECTION FAIL: "+e);e.printStackTrace();
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture,t->Bukkit.shutdown(),20);
  }
}
