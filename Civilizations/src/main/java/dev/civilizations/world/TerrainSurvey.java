package dev.civilizations.world;

import dev.civilizations.core.Pos;
import java.util.concurrent.CompletableFuture;
import org.bukkit.World;

/** Asynchronous immutable observations; execution remains on the owning Folia region. */
@FunctionalInterface
public interface TerrainSurvey {
  CompletableFuture<Terrain> capture(World world, Pos origin, int radius);
}
