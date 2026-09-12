package dev.hearth.path;

import dev.hearth.util.BlockUtils;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A resumable, budgeted A* search (Baritone-style ideas, original implementation).
 *
 * <p>Each tick the owner may call {@link #step(int)} with a budget of node expansions.
 * The search keeps its open/closed lists in memory so it can resume on later ticks,
 * which keeps the server smooth even for long paths.
 *
 * <p>Cost model (per step):
 * <ul>
 *   <li>horizontal move: 1.0 (+ block penalty)</li>
 *   <li>jump up 1: 2.0 (needs two clear blocks above the target)</li>
 *   <li>drop d (1..3): 1.0 + 0.25*d</li>
 *   <li>drop d > 3: heavy damage penalty (villagers prefer stairs)</li>
 *   <li>water: +2.0 swim penalty (still allowed to cross rivers)</li>
 *   <li>lava/fire: never</li>
 * </ul>
 */
public class PathSearch {

    public enum Status { RUNNING, DONE, FAILED }

    private final World world;
    private final int startX, startY, startZ;
    private final int goalX, goalY, goalZ;
    private final int maxRadius;
    private final int maxDepth;

    private final PriorityQueue<PathNode> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
    private final Map<Long, PathNode> closed = new HashMap<>();

    private int expansions = 0;
    private PathNode goalNode = null;
    private int lastStepped = 0;

    public PathSearch(World world, int startX, int startY, int startZ,
                      int goalX, int goalY, int goalZ, int maxRadius, int maxDepth) {
        this.world = world;
        this.startX = startX; this.startY = startY; this.startZ = startZ;
        this.goalX = goalX; this.goalY = goalY; this.goalZ = goalZ;
        this.maxRadius = maxRadius;
        this.maxDepth = maxDepth;

        PathNode start = new PathNode(startX, startY, startZ);
        start.g = 0;
        start.f = heuristic(startX, startY, startZ);
        open.add(start);
    }

    private double heuristic(int x, int y, int z) {
        int dx = Math.abs(x - goalX);
        int dy = Math.abs(y - goalY);
        int dz = Math.abs(z - goalZ);
        // Octile-ish, vertical weighted higher.
        int hz = Math.max(dx, dz);
        int h1 = dx + dz - hz;
        return h1 + 1.41 * hz + 1.6 * dy;
    }

    /**
     * Run up to {@code budget} expansions. Returns DONE when the goal is reached,
     * FAILED when exhausted, RUNNING otherwise.
     */
    public Status step(int budget) {
        if (goalNode != null) {
            return Status.DONE;
        }
        int did = 0;
        while (did < budget && expansions < maxDepth && !open.isEmpty()) {
            PathNode cur = open.poll();
            long key = PathNode.pack(cur.x, cur.y, cur.z);
            if (closed.containsKey(key)) {
                continue;
            }
            closed.put(key, cur);
            expansions++;
            did++;

            if (cur.x == goalX && cur.y == goalY && cur.z == goalZ) {
                goalNode = cur;
                return Status.DONE;
            }

            expand(cur);
        }
        lastStepped = did;
        if (open.isEmpty()) {
            return Status.FAILED;
        }
        return Status.RUNNING;
    }

    private void expand(PathNode cur) {
        // 4 horizontal directions
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};
        for (int i = 0; i < 4; i++) {
            consider(cur, cur.x + dx[i], cur.y, cur.z + dz[i], 1.0);
        }
        // Jump up 1
        considerJump(cur, cur.x, cur.y + 1, cur.z, 2.0);
        // Drop 1..3 (cheaper than jumping, but penalized for fall damage beyond 3)
        for (int d = 1; d <= 3; d++) {
            considerDrop(cur, cur.x, cur.y - d, cur.z, 1.0 + 0.25 * d, d);
        }
        // Big drops allowed but very expensive (villagers strongly prefer stairs)
        considerDrop(cur, cur.x, cur.y - 5, cur.z, 8.0, 5);
        considerDrop(cur, cur.x, cur.y - 8, cur.z, 20.0, 8);
    }

    private void consider(PathNode cur, int nx, int ny, int nz, double baseCost) {
        if (!inBounds(nx, ny, nz)) {
            return;
        }
        Material t = world.getBlockAt(nx, ny, nz).getType();
        if (!BlockUtils.isPassable(t)) {
            return;
        }
        if (!groundBelow(nx, ny, nz)) {
            return;
        }
        double cost = baseCost + BlockUtils.movementCost(t);
        relax(cur, nx, ny, nz, cost);
    }

    private void considerJump(PathNode cur, int nx, int ny, int nz, double cost) {
        if (!inBounds(nx, ny, nz)) {
            return;
        }
        Material t = world.getBlockAt(nx, ny, nz).getType();
        if (!BlockUtils.isPassable(t)) {
            return;
        }
        // Headroom: target and the block above it must be passable.
        Material above = world.getBlockAt(nx, ny + 1, nz).getType();
        if (!BlockUtils.isPassable(above)) {
            return;
        }
        Material below = world.getBlockAt(nx, ny - 1, nz).getType();
        if (!below.isSolid() && !BlockUtils.isLiquid(below)) {
            return;
        }
        relax(cur, nx, ny, nz, cost + BlockUtils.movementCost(t));
    }

    private boolean groundBelow(int x, int y, int z) {
        if (y == world.getMinHeight()) {
            return true;
        }
        Material below = world.getBlockAt(x, y - 1, z).getType();
        if (below.isSolid() || BlockUtils.isLiquid(below)) {
            return true;
        }
        // Beds support standing (villagers sleep on top of them).
        return isBed(below);
    }

    private static boolean isBed(Material m) {
        String n = m.name();
        return n.endsWith("_BED");
    }

    private void considerDrop(PathNode cur, int nx, int ny, int nz, double cost, int fall) {
        if (ny < world.getMinHeight()) {
            return;
        }
        if (!inBounds(nx, ny, nz)) {
            return;
        }
        Material t = world.getBlockAt(nx, ny, nz).getType();
        if (!BlockUtils.isPassable(t)) {
            return;
        }
        if (!groundBelow(nx, ny, nz)) {
            return;
        }
        relax(cur, nx, ny, nz, cost + BlockUtils.movementCost(t));
    }

    private boolean inBounds(int x, int y, int z) {
        if (y < world.getMinHeight() || y > world.getMaxHeight()) {
            return false;
        }
        int dx = x - startX;
        int dz = z - startZ;
        if (dx * dx + dz * dz > maxRadius * maxRadius) {
            return false;
        }
        if (!BlockUtils.chunkLoaded(world, x, z)) {
            return false;
        }
        return true;
    }

    private void relax(PathNode cur, int nx, int ny, int nz, double cost) {
        long key = PathNode.pack(nx, ny, nz);
        if (closed.containsKey(key)) {
            return;
        }
        double newG = cur.g + cost;
        PathNode node = new PathNode(nx, ny, nz);
        node.g = newG;
        node.f = newG + heuristic(nx, ny, nz);
        node.parent = cur;
        // Lazy deletion: duplicates are pruned when polled against the closed set.
        open.add(node);
    }

    /**
     * Reconstruct the path (start excluded, goal included). Empty if not done.
     */
    public List<int[]> path() {
        List<int[]> out = new ArrayList<>();
        if (goalNode == null) {
            return out;
        }
        PathNode cur = goalNode;
        while (cur != null) {
            out.add(new int[]{cur.x, cur.y, cur.z});
            cur = cur.parent;
        }
        java.util.Collections.reverse(out);
        return out;
    }

    public int expansions() {
        return expansions;
    }

    public int lastStepped() {
        return lastStepped;
    }

    public boolean reached() {
        return goalNode != null;
    }
}
