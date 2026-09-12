package dev.hearth.build;

import org.bukkit.Location;
import org.bukkit.Material;

import java.util.UUID;

/**
 * A single build action: place (or mine) one block at a location.
 *
 * <p>Claims (v1.2 "society"): several villager brains pick jobs from the same
 * village plans, so a job can be <em>claimed</em> by exactly one villager.
 * The claim fields are volatile (written by one brain's region thread, read
 * by every other's) and claims expire after a short TTL, so a villager that
 * dies or gets stuck never permanently locks a block out of the work queue.
 */
public class BuildJob {
    public final Location location;
    public final Material material;
    public final Kind kind;

    /** Who currently has this job, or null when unclaimed. */
    public volatile UUID claimedBy;
    /** When the claim was made (System.currentTimeMillis()). */
    public volatile long claimedAt;

    public enum Kind { PLACE, MINE, LIGHT }

    public BuildJob(Location location, Material material, Kind kind) {
        this.location = location;
        this.material = material;
        this.kind = kind;
    }

    public Location getLocation() {
        return location;
    }

    public Material getMaterial() {
        return material;
    }

    public Kind getKind() {
        return kind;
    }

    // ---- claims ----

    /**
     * True if this job is free for {@code me} to take right now: either
     * unclaimed, already claimed by {@code me}, or the claim has gone stale.
     */
    public boolean isAvailableTo(UUID me, long now, long ttlMs) {
        if (claimedBy == null) {
            return true;
        }
        if (claimedBy.equals(me)) {
            return true;
        }
        return now - claimedAt > ttlMs;
    }

    /**
     * Take (or renew) the claim for this villager.
     */
    public void claim(UUID me, long now) {
        this.claimedBy = me;
        this.claimedAt = now;
    }

    @Override
    public String toString() {
        String base = kind + " " + material + " @ " + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
        if (claimedBy != null) {
            base += " (claimed by " + claimedBy.toString().substring(0, 8) + ")";
        }
        return base;
    }
}
