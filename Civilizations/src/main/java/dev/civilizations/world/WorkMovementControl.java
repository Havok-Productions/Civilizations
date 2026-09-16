package dev.civilizations.world;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.Consumer;
import org.bukkit.entity.Villager;

/** Narrow native adapter: pause competing behavior packages, retain navigation and physics. */
public final class WorkMovementControl {
  private final Villager actor;
  private final Consumer<String> log;
  private Object handle, brain;
  private Method getBrain, level, stopAll, removeAll, refresh;
  private Method regionTick;
  private java.lang.reflect.Field activatedTick;
  private boolean controlling, unavailable;

  public WorkMovementControl(Villager actor, Consumer<String> log) {
    this.actor = actor;
    this.log = log;
  }

  public void working(boolean work) {
    if (unavailable || !work && !controlling) return;
    try {
      if (handle == null) {
        handle = actor.getClass().getMethod("getHandle").invoke(actor);
        getBrain = handle.getClass().getMethod("getBrain");
        level = handle.getClass().getMethod("level");
        brain = getBrain.invoke(handle);
        stopAll = method(brain.getClass(), "stopAll", 2);
        removeAll = method(brain.getClass(), "removeAllBehaviors", 0);
        refresh = method(handle.getClass(), "refreshBrain", 1);
        activatedTick = handle.getClass().getField("activatedTick");
        regionTick =
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
                .getMethod("getCurrentTick");
      }
      // EntityScheduler callbacks still run when EAR suppresses native movement ticks.
      // Renew a short owning-region lease only during work, without changing server-wide settings.
      if (work) {
        if (actor.isSleeping()) actor.wakeup();
        long tick = ((Number) regionTick.invoke(null)).longValue();
        activatedTick.setLong(handle, Math.max(activatedTick.getLong(handle), tick + 10));
        if (controlling) return;
      }
      Object world = level.invoke(handle);
      if (work) {
        brain = getBrain.invoke(handle);
        stopAll.invoke(brain, world, handle);
        removeAll.invoke(brain);
        controlling = true;
      } else {
        refresh.invoke(handle, world);
        controlling = false;
      }
    } catch (ReflectiveOperationException | RuntimeException e) {
      // No unsafe setAI(false): that would also stop native movement.
      unavailable = true;
      log.accept(
          "Work movement adapter unavailable for "
              + actor.getUniqueId()
              + ": "
              + e.getClass().getSimpleName()
              + "; native behavior remains active");
    }
  }

  public boolean controlling() {
    return controlling;
  }

  private static Method method(Class<?> type, String name, int count) throws NoSuchMethodException {
    return Arrays.stream(type.getMethods())
        .filter(m -> m.getName().equals(name) && m.getParameterCount() == count)
        .findFirst()
        .orElseThrow(() -> new NoSuchMethodException(name));
  }
}
