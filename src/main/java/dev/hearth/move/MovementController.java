package dev.hearth.move;

import dev.hearth.HearthPlugin;
import dev.hearth.brain.VillagerBrain;
import org.bukkit.Location;
import org.bukkit.entity.Villager;
import org.bukkit.util.Vector;

/**
 * Moves a single villager along its current path at roughly normal walking speed.
 *
 * <p>Folia: this is always invoked from the villager's own entity region thread
 * (see {@link dev.hearth.HearthPlugin#startBrain}), so {@code teleport}/{@code setVelocity}/
 * {@code setRotation} happen on the region that owns the entity — Folia-correct.
 *
 * <p>Strategy (keeps them looking like ordinary villagers):
 * <ul>
 *   <li>Nudge the villager toward the next waypoint using a small
 *       velocity impulse (gravity, jumps and friction all stay vanilla).</li>
 *   <li>If the villager makes almost no progress (stuck on a ledge, mob, etc.),
 *       take ONE small "step" (teleport of up to {@code step-assist-distance}).
 *       This reads as a natural step, not a teleport across the map.</li>
 *   <li>Never exceed {@code max-speed} blocks per tick (default 0.22 ~ brisk walk).</li>
 * </ul>
 */
public class MovementController {

    private static final long STUCK_MS = 3000;

    private final HearthPlugin plugin;

    public MovementController(HearthPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Advance one brain that is currently traveling. Must be called on the villager's
     * region thread.
     */
    public void stepFor(Villager v, VillagerBrain brain) {
        if (v == null || !v.isValid() || v.isDead()) {
            return;
        }
        if (brain == null
                || (brain.getState() != dev.hearth.brain.VillagerState.TRAVEL
                && brain.getState() != dev.hearth.brain.VillagerState.FLEE)) {
            return;
        }
        stepTowards(v, brain);
    }

    private void stepTowards(Villager v, VillagerBrain brain) {
        Location target = brain.getCurrentTarget();
        if (target == null || !target.getWorld().equals(v.getWorld())) {
            brain.arrived();
            return;
        }
        Location here = v.getLocation();
        Vector to = target.toVector().subtract(here.toVector());
        double dist = to.length();
        if (dist < 0.45) {
            // Close enough: snap to the waypoint center (keeps grid alignment for building).
            Location snap = target.clone();
            snap.setY(target.getY() + 0.001);
            v.teleport(snap);
            brain.arrived();
            return;
        }

        double maxStep = plugin.maxSpeed(); // blocks this tick
        Vector dir = to.clone().normalize();

        // Horizontal nudge.
        Vector vel = new Vector(dir.getX() * 0.12, 0.0, dir.getZ() * 0.12);

        // Vertical: jump if going up, gentle hop to stay attached when flat.
        double dy = target.getY() - here.getY();
        if (dy > 0.5) {
            vel.setY(0.42); // jump
        } else if (dy < -3.0) {
            vel.setY(0.0); // fall naturally
        } else {
            vel.setY(0.02);
        }

        // Cap horizontal speed.
        double horizontal = Math.sqrt(vel.getX() * vel.getX() + vel.getZ() * vel.getZ());
        if (horizontal > maxStep) {
            vel.multiply(maxStep / horizontal);
        }
        v.setVelocity(vel);

        // Face the direction of travel (looks natural).
        if (plugin.faceDirection()) {
            float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
            float pitch = (float) Math.toDegrees(Math.asin(dy / Math.max(1e-5, dist)));
            v.setRotation(yaw, pitch);
        }

        // Stuck detection: if we haven't moved meaningfully in a while, take one small step.
        long now = System.currentTimeMillis();
        double moved = here.distance(brain.lastPosition());
        if (plugin.stepAssist() && now - brain.lastMoveTime() > STUCK_MS && moved < 0.35) {
            Location step = here.clone().add(dir.clone().multiply(Math.min(plugin.stepAssistDistance(), dist)));
            step.setY(here.getY() + Math.min(0.6, Math.max(-1.0, dy)));
            v.teleport(step);
            brain.noteProgress();
        }
        brain.lastPositionSet(here.clone());
        brain.lastMoveTimeSet(now);
    }
}
