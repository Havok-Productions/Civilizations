package dev.civilizations.world;

import java.util.function.Predicate;

/** Entity-thread arbiter: the first activity that handles a tick owns it exclusively. */
final class WorkerActivities {
  enum Owner {
    PAUSED,
    EMERGENCY,
    THREAT,
    SLEEP,
    MAINTENANCE,
    RECOVERY,
    YIELD,
    PROJECT_SUPPLIES,
    DELIVERY,
    COURIER_WAIT,
    PREREQUISITE_WAIT,
    WORK
  }

  private Owner owner = Owner.WORK;
  private boolean dispatching;

  Owner owner() {
    return owner;
  }

  void tick(Predicate<Owner> handle) {
    if (dispatching) throw new IllegalStateException("Recursive worker activity dispatch");
    dispatching = true;
    try {
      for (Owner candidate : Owner.values()) {
        owner = candidate;
        if (handle.test(candidate)) return;
      }
      throw new IllegalStateException("No worker activity handled tick");
    } finally {
      dispatching = false;
    }
  }
}
