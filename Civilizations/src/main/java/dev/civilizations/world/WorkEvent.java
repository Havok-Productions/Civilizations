package dev.civilizations.world;

import org.bukkit.block.Block;
import org.bukkit.entity.Villager;
import org.bukkit.event.*;

/** Region-thread event for protection plugins. Cancel before any resource/world mutation. */
public final class WorkEvent extends Event implements Cancellable {
  private static final HandlerList HANDLERS = new HandlerList();
  private final Villager villager;
  private final Block block;
  private final String action;
  private boolean cancelled;

  public WorkEvent(Villager villager, Block block, String action) {
    this.villager = villager;
    this.block = block;
    this.action = action;
  }

  public Villager getVillager() {
    return villager;
  }

  public Block getBlock() {
    return block;
  }

  public String getAction() {
    return action;
  }

  @Override
  public boolean isCancelled() {
    return cancelled;
  }

  @Override
  public void setCancelled(boolean value) {
    cancelled = value;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
