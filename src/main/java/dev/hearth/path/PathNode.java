package dev.hearth.path;

/**
 * A node in the pathfinding graph: one block position.
 */
public final class PathNode {
    public final int x, y, z;
    public double g;      // cost from start
    public double f;      // g + heuristic
    public PathNode parent;

    public PathNode(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.g = Double.POSITIVE_INFINITY;
        this.f = Double.POSITIVE_INFINITY;
    }

    public static long pack(int x, int y, int z) {
        // x,z: 26 signed bits each (±33M blocks), y: 9 bits (-256..255).
        // Total 61 bits: no collisions across the full Minecraft coordinate range.
        return ((x & 0x3FFFFFFL) << 35) | ((y & 0x1FFL) << 26) | (z & 0x3FFFFFFL);
    }
}
