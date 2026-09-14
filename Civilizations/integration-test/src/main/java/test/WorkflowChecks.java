package test;

import dev.civilizations.*;
import dev.civilizations.core.*;
import dev.civilizations.design.*;
import dev.civilizations.world.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable world only. Seeds terrain and a lighting goal, never tools, fuel or a mine plan. */
final class WorkflowChecks {
  private final JavaPlugin fixture;
  private CivilizationsPlugin plugin;
  private Settlement village;
  private Villager actor;
  private int base;
  private Pos coal;
  private boolean pick,wood,table;
  private long started;
  WorkflowChecks(JavaPlugin fixture) { this.fixture=fixture; }
  void start() { Bukkit.getGlobalRegionScheduler().runDelayed(fixture,t -> prepare(),80); }
  void prepare() {
    plugin=(CivilizationsPlugin)Bukkit.getPluginManager().getPlugin("Civilizations");
    World w=Bukkit.getWorlds().getFirst();w.setTime(1000);
    w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);w.setGameRule(GameRule.DO_MOB_SPAWNING,false);
    List<CompletableFuture<Void>> waits=new ArrayList<>();
    for(int x=-3;x<=3;x++) for(int z=-3;z<=3;z++) {
      int cx=x,cz=z;CompletableFuture<Void> f=new CompletableFuture<>();waits.add(f);
      w.getChunkAtAsync(cx,cz,true).thenAccept(c -> Bukkit.getRegionScheduler().execute(fixture,w,cx,cz,() -> {
        c.addPluginChunkTicket(fixture);f.complete(null);
      })).exceptionally(e -> {f.completeExceptionally(e);return null;});
    }
    CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new)).thenRun(() ->
      Bukkit.getRegionScheduler().runDelayed(fixture,new Location(w,0,1,0),t -> seed(w),60));
  }
  @SuppressWarnings("unchecked") void seed(World w) {
    try {
      base=w.getHighestBlockYAt(0,0)+1;
      for(int y=base;y<base+4;y++) w.getBlockAt(7,y,0).setType(Material.BIRCH_LOG,false);
      for(int x=5;x<=9;x++) for(int z=-2;z<=2;z++) w.getBlockAt(x,base+4,z).setType(Material.BIRCH_LEAVES,false);
      coal=new Pos(0,base-4,-15);w.getBlockAt(coal.x(),coal.y(),coal.z()).setType(Material.COAL_ORE,false);
      Pos chest=new Pos(2,base,2);w.getBlockAt(chest.x(),chest.y(),chest.z()).setType(Material.CHEST,false);
      Pos bedPos=new Pos(-3,base,0);Bed bed=(Bed)Material.WHITE_BED.createBlockData();
      bed.setFacing(BlockFace.NORTH);bed.setPart(Bed.Part.FOOT);w.getBlockAt(-3,base,0).setBlockData(bed,false);
      bed=(Bed)bed.clone();bed.setPart(Bed.Part.HEAD);w.getBlockAt(-3,base,-1).setBlockData(bed,false);
      Settlement.Data data=new Settlement.Data();data.world=w.getUID().toString();data.center=new Pos(0,base,0);
      data.radius=12;data.chest=chest;data.beds.add(bedPos);village=new Settlement(data);
      Job light=new Job(Job.Kind.PLACE,"workflow-lights",new Pos(4,base,4),new Pos(3,base,4),"TORCH","AIR",null);
      village.addDesign(new DesignRecord("workflow-lights","lights","Light the village","{}",Map.of("TORCH",1),1,System.currentTimeMillis()),List.of(light),Set.of(light.target),1);
      Field settlements=CivilizationsPlugin.class.getDeclaredField("settlements");settlements.setAccessible(true);
      ((Map<String,Settlement>)settlements.get(plugin)).put(village.id(),village);
      actor=w.spawn(new Location(w,0.5,base,0.5),Villager.class);actor.setAdult();actor.setPersistent(true);
      attach(actor);village.stock(Map.of(),System.currentTimeMillis());
      village.knowledge().need("COAL","workflow-lights",System.currentTimeMillis());
      started=System.currentTimeMillis();plugin.requestPlan(village,w);
      fixture.getLogger().info("WORKFLOW SEEDED empty worker/chest, birch tree, buried coal, lighting goal; no mine jobs supplied. base="+base);
      actor.getScheduler().runAtFixedRate(fixture,t -> {
        try { if(check(w)) t.cancel(); }
        catch(Throwable e) { fail(e);t.cancel(); }
      },() -> {},100,100);
    } catch(Throwable e) { fail(e); }
  }
  private void attach(Villager v) throws Exception {
    Method attach=CivilizationsPlugin.class.getDeclaredMethod("attach",Villager.class,Settlement.class);
    attach.setAccessible(true);attach.invoke(plugin,v,village);
  }
  boolean check(World w) throws Exception {
    if(java.nio.file.Files.exists(java.nio.file.Path.of("stop-workflow"))) { Bukkit.getGlobalRegionScheduler().execute(fixture,Bukkit::shutdown);return true; }
    Map<String,Integer> inv=InventoryOps.summary(actor.getInventory());
    pick|=inv.getOrDefault("WOODEN_PICKAXE",0)>0;
    wood|=w.getBlockAt(7,base+2,0).getType().isAir();table|=village.craftingTable()!=null;
    boolean torch=w.getBlockAt(4,base,4).getType()==Material.TORCH;
    boolean mined=w.getBlockAt(coal.x(),coal.y(),coal.z()).getType().isAir();
    fixture.getLogger().info("WORKFLOW PROGRESS inv="+inv+" pos="+actor.getLocation().toVector()+" jobs="+
      village.jobs().stream().filter(j -> j.complete).count()+"/"+village.jobs().size()+" "+plugin.worker(actor.getUniqueId().toString()).status());
    Chest chest=(Chest)w.getBlockAt(2,base,2).getState();
    if(!torch && InventoryOps.total(chest.getBlockInventory())>0) throw new AssertionError("Deposited before lighting task complete");
    if(torch) {
      if(!pick || !wood || !table || !mined) throw new AssertionError("Missing workflow stage: "+List.of(pick,wood,table,mined));
      if(village.designs().stream().noneMatch(d -> d.kind().equals("mine"))) throw new AssertionError("Model never designed the mine");
      long mineSteps=village.jobs().stream().filter(j -> j.kind==Job.Kind.MINE && j.complete).count();
      if(mineSteps<3) throw new AssertionError("No real shaft excavation");
      fixture.getLogger().info("WORKFLOW PASS: birch -> server recipes -> placed table -> wooden pickaxe -> AI designed mine -> "+mineSteps+" mined blocks -> coal -> crafted/placed torch. inventory="+inv+" AI="+plugin.inference().status());
      village.paused(true);plugin.worker(actor.getUniqueId().toString()).stop();actor.setAI(false);
      interactions(w);
      Bukkit.getGlobalRegionScheduler().runDelayed(fixture,t -> Bukkit.shutdown(),40);
      return true;
    }
    if(System.currentTimeMillis()-started>480_000) throw new AssertionError("Workflow deadline exceeded");
    return false;
  }
  void interactions(World w) throws Exception {
    actor.getInventory().clear();
    Villager peer=w.spawn(actor.getLocation().clone().add(1,0,0),Villager.class);peer.setAdult();attach(peer);
    plugin.worker(peer.getUniqueId().toString()).stop();peer.setAI(false);
    peer.getInventory().addItem(new ItemStack(Material.COAL,2),new ItemStack(Material.WOODEN_PICKAXE));
    village.remember(peer.getUniqueId().toString(),"Observed coal ore in a safe shaft",true);
    new NearbyWork(plugin,actor,village).tick(Map.of("COAL",1),System.currentTimeMillis());
    if(InventoryOps.count(actor.getInventory(),Material.COAL)!=1 || InventoryOps.count(peer.getInventory(),Material.COAL)!=1
        || InventoryOps.count(peer.getInventory(),Material.WOODEN_PICKAXE)!=1) throw new AssertionError("Peer transfer conservation/retention");
    if(village.memories(actor.getUniqueId().toString()).stream().noneMatch(m -> m.result().startsWith("Heard ")))
      throw new AssertionError("Nearby fact not exchanged");
    Item dropped=w.dropItem(actor.getLocation(),new ItemStack(Material.BIRCH_LOG,2));dropped.setPickupDelay(0);
    new NearbyWork(plugin,actor,village).tick(Map.of("LOG",1),System.currentTimeMillis());
    if(InventoryOps.count(actor.getInventory(),Material.BIRCH_LOG)!=1 || dropped.getItemStack().getAmount()!=1)
      throw new AssertionError("Partial pickup must conserve the uncollected stack");
    actor.getInventory().clear();
    Item coalDrop=w.dropItem(actor.getLocation(),new ItemStack(Material.COAL));coalDrop.setPickupDelay(0);
    new NearbyWork(plugin,actor,village).tick(Map.of("COAL",1),System.currentTimeMillis());
    int leftover=InventoryOps.count(peer.getInventory(),Material.COAL)+(coalDrop.isValid()?coalDrop.getItemStack().getAmount():0);
    if(InventoryOps.count(actor.getInventory(),Material.COAL)!=1 || leftover!=1) throw new AssertionError("Multiple sources oversupplied one request");
    actor.getInventory().clear();
    for(int slot=0;slot<actor.getInventory().getSize();slot++) actor.getInventory().setItem(slot,new ItemStack(Material.COBBLESTONE,64));
    int before=dropped.getItemStack().getAmount();
    new NearbyWork(plugin,actor,village).tick(Map.of("LOG",1),System.currentTimeMillis());
    if(dropped.getItemStack().getAmount()!=before) throw new AssertionError("Full inventory lost dropped items");
    actor.getInventory().clear();
    Chest storage=(Chest)w.getBlockAt(2,base,2).getState();storage.getBlockInventory().clear();
    storage.getBlockInventory().addItem(new ItemStack(Material.TORCH,3));village.stock(Map.of("TORCH",3),System.currentTimeMillis());
    Pos at=new Pos(actor.getLocation().getBlockX(),actor.getLocation().getBlockY(),actor.getLocation().getBlockZ());
    boolean took=ChestSupplies.obtain(plugin,actor,village,new WorkerNavigation(plugin,actor,village,(time,reason) -> { throw new AssertionError(reason); }),"TORCH",Map.of(),true,at,System.currentTimeMillis());
    if(!took || InventoryOps.count(actor.getInventory(),Material.TORCH)!=1 || InventoryOps.count(storage.getBlockInventory(),Material.TORCH)!=2) throw new AssertionError("Stored finished torch not reused exactly");
    fixture.getLogger().info("STORAGE/GUARDS PASS: multi-source request cap, full inventory preserves drops, exact finished-item reuse");
    fixture.getLogger().info("INTERACTIONS PASS: nearby request/handoff, tool retention, fact exchange, partial dropped-item pickup");
  }
  void fail(Throwable e) {
    fixture.getLogger().severe("WORKFLOW FAIL: "+e);e.printStackTrace();
    Bukkit.getGlobalRegionScheduler().runDelayed(fixture,t -> Bukkit.shutdown(),20);
  }
}
