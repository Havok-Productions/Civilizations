package dev.hearth.build;

import org.bukkit.Location;
import org.bukkit.Material;

/**
 * A single build action: place (or mine) one block at a location.
 */
public class BuildJob {
    public final Location location;
    public final Material material;
    public final Kind kind;

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

    @Override
    public String toString() {
        return kind + " " + material + " @ " + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }
}
