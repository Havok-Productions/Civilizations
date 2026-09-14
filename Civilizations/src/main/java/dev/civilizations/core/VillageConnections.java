package dev.civilizations.core;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/** Coordinates membership changes with in-flight actions; never waits for a region callback. */
public final class VillageConnections {
  private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock(true);

  public void read(Runnable work) {
    gate.readLock().lock();
    try {
      work.run();
    } finally {
      gate.readLock().unlock();
    }
  }

  public <T> T change(Supplier<T> work) {
    gate.writeLock().lock();
    try {
      return work.get();
    } finally {
      gate.writeLock().unlock();
    }
  }

  public static boolean near(Pos a, Pos b, int distance, int height) {
    return a.horizontal2(b) <= (long) distance * distance && Math.abs(a.y() - b.y()) <= height;
  }

  public static boolean connected(Settlement a, Settlement b, int distance, int height) {
    return a.world().equals(b.world())
        && a.connectionPoints().stream()
            .anyMatch(
                p -> b.connectionPoints().stream().anyMatch(q -> near(p, q, distance, height)));
  }

  /** Pure migration: real inventories stay in the world; stock is observed again after merging. */
  public static Settlement combine(Collection<Settlement> villages) {
    List<Settlement> ordered =
        villages.stream().sorted(Comparator.comparing(Settlement::id)).toList();
    if (ordered.isEmpty()) throw new IllegalArgumentException("No villages to combine");
    Settlement.Data d = ordered.getFirst().snapshot();
    d.supplyNeeds = new ArrayList<>(d.supplyNeeds);
    d.progress = new LinkedHashMap<>(d.progress);
    for (Settlement source : ordered.subList(1, ordered.size())) {
      if (!d.world.equals(source.world())) throw new IllegalArgumentException("Different worlds");
      Settlement.Data from = source.snapshot();
      String suffix = "@" + from.id;
      java.util.function.Function<String, String> project =
          p -> p == null || p.isEmpty() ? "" : p + suffix;
      d.absorbedIds.add(from.id);
      d.absorbedIds.addAll(from.absorbedIds);
      for (Pos p : from.chests) if (!d.chests.contains(p)) d.chests.add(p);
      for (Pos p : from.areas) if (!d.areas.contains(p)) d.areas.add(p);
      for (Pos p : from.beds) if (!d.beds.contains(p)) d.beds.add(p);
      from.projects.forEach(p -> d.projects.add(project.apply(p)));
      for (Job j : from.jobs) {
        j.project = project.apply(j.project);
        d.jobs.add(j);
      }
      from.agents.forEach(
          (id, a) -> {
            a.taskProject = project.apply(a.taskProject);
            a.committedProjects = new HashSet<>(a.committedProjects.stream().map(project).toList());
            d.agents.putIfAbsent(id, a);
          });
      d.memberPositions.putAll(from.memberPositions);
      d.playerBlocks.addAll(from.playerBlocks);
      from.repairBlocks.forEach(
          block -> {
            if (d.repairBlocks.stream().noneMatch(b -> b.position().equals(block.position())))
              d.repairBlocks.add(block);
          });
      d.designReservations.addAll(from.designReservations);
      d.designFeedback.addAll(from.designFeedback);
      from.designs.forEach(
          record ->
              d.designs.add(
                  new DesignRecord(
                      project.apply(record.project()),
                      record.kind(),
                      record.purpose(),
                      record.blueprint(),
                      record.materials(),
                      record.jobs(),
                      record.created(),
                      record.origin() == null ? from.center : record.origin())));
      from.supplyNeeds.forEach(
          s ->
              d.supplyNeeds.add(
                  new VillageKnowledge.Supply(
                      s.material(), project.apply(s.project()), s.observedAt())));
      from.progress.forEach(
          (id, p) ->
              d.progress.put(
                  id,
                  new VillageKnowledge.Progress(
                      project.apply(p.goal()),
                      p.step(),
                      p.blocker(),
                      p.lastResult(),
                      p.updatedAt())));
      // Resource absence and routes from separate work groups require fresh shared observations.
      if (d.craftingTable == null) d.craftingTable = from.craftingTable;
      d.paused |= from.paused;
    }
    d.blockedFacts = new ArrayList<>();
    if (d.chest == null && !d.chests.isEmpty()) d.chest = d.chests.getFirst();
    return new Settlement(d);
  }
}
