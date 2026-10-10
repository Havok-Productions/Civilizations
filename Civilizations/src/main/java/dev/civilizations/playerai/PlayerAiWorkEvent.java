package dev.civilizations.playerai;

import org.bukkit.block.Block;
import org.bukkit.entity.Mannequin;
import org.bukkit.event.*;

/** Protection integration for NPC actions; fired on the owning region before any mutation. */
public final class PlayerAiWorkEvent extends Event implements Cancellable {
  private static final HandlerList HANDLERS = new HandlerList();
  private final Mannequin actor;
  private final Block block;
  private final String action;
  private boolean cancelled;

  public PlayerAiWorkEvent(Mannequin actor, Block block, String action) {
    this.actor = actor;
    this.block = block;
    this.action = action;
  }

  public Mannequin getActor() {
    return actor;
  }

  public Block getBlock() {
    return block;
  }

  public String getAction() {
    return action;
  }

  public boolean isCancelled() {
    return cancelled;
  }

  public void setCancelled(boolean value) {
    cancelled = value;
  }

  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
