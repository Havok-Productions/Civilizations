package dev.hearth.path;

import org.bukkit.Location;

import java.util.List;

/**
 * Result of a completed path search: a list of block waypoints (start excluded).
 */
public class PathResult {
    public final List<int[]> blocks;
    public final int expansions;
    public final long createdAt;

    public PathResult(List<int[]> blocks, int expansions) {
        this.blocks = blocks;
        this.expansions = expansions;
        this.createdAt = System.currentTimeMillis();
    }

    public int size() {
        return blocks.size();
    }

    public int[] blockAt(int i) {
        return blocks.get(i);
    }

    public Location locationAt(int i, org.bukkit.World world) {
        int[] b = blocks.get(i);
        return new Location(world, b[0] + 0.5, b[1], b[2] + 0.5);
    }
}
