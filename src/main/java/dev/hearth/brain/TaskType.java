package dev.hearth.brain;

/**
 * The kinds of work a village can do.
 */
public enum TaskType {
    IDLE,
    CHEST,      // place/find the community chest
    WALL,       // build the village wall
    REPAIR,     // repair missing wall blocks
    LIGHT,      // add lighting
    GATHER,     // gather surface resources (stone, dirt, logs) into the chest
    MINE,       // dig/mine the underground tunnel
    SLEEP       // go to bed (night)
}
