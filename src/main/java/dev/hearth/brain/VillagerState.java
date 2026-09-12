package dev.hearth.brain;

/**
 * Per-villager state machine states.
 */
public enum VillagerState {
    IDLE,     // deciding what to do
    TRAVEL,   // moving along a path toward a target
    WORK,     // performing an action at the target (place/mine)
    SLEEP,    // in bed (night)
    FLEE      // running from a nearby mob
}
