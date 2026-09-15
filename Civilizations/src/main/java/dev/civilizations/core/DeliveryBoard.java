package dev.civilizations.core;

import java.util.*;

/**
 * Region-independent promises. Inventories stay with their entities; this board never moves items.
 */
public final class DeliveryBoard {
  public record Worker(
      Pos position, String task, Map<String, Integer> need, Map<String, Integer> offer, long seen) {
    public Worker {
      need = Map.copyOf(need);
      offer = Map.copyOf(offer);
    }
  }

  public record Delivery(
      String donor, String recipient, String task, String material, int amount) {}

  private final Map<String, Worker> workers = new HashMap<>();
  private final Map<String, Delivery> active = new HashMap<>();

  public synchronized void publish(String id, Worker worker) {
    workers.put(id, worker);
    active
        .values()
        .removeIf(
            d ->
                d.recipient().equals(id)
                    && (!d.task().equals(worker.task()) || wanted(worker, d.material()) <= 0));
  }

  public synchronized void remove(String id) {
    workers.remove(id);
    active.values().removeIf(d -> d.donor().equals(id) || d.recipient().equals(id));
  }

  public synchronized void cancel(String donor) {
    active.remove(donor);
  }

  public synchronized Worker worker(String id) {
    return workers.get(id);
  }

  public synchronized Delivery incoming(String recipient, long now) {
    return active.values().stream()
        .filter(d -> d.recipient().equals(recipient) && amount(d, now) > 0)
        .findFirst()
        .orElse(null);
  }

  public synchronized Delivery claim(String donor, long now) {
    active.values().removeIf(d -> !fresh(d.donor(), now) || !fresh(d.recipient(), now));
    Worker from = workers.get(donor);
    if (!fresh(donor, now)) return null;
    Delivery current = active.get(donor);
    if (current != null) {
      Worker to = workers.get(current.recipient());
      if (from.offer().getOrDefault(current.material(), 0) > 0
          && wanted(to, current.material()) > 0) return current;
      active.remove(donor);
    }
    // Do not create cycles with two couriers chasing one another.
    if (active.values().stream().anyMatch(d -> d.recipient().equals(donor))) return null;
    for (var entry :
        workers.entrySet().stream()
            .sorted(
                Comparator.comparingLong(e -> e.getValue().position().distance2(from.position())))
            .toList()) {
      String recipient = entry.getKey();
      if (recipient.equals(donor)
          || !fresh(recipient, now)
          || active.containsKey(recipient)
          || active.values().stream().anyMatch(d -> d.recipient().equals(recipient))) continue;
      for (var offer : new TreeMap<>(from.offer()).entrySet()) {
        int amount = Math.min(offer.getValue(), wanted(entry.getValue(), offer.getKey()));
        if (amount <= 0) continue;
        Delivery delivery =
            new Delivery(donor, recipient, entry.getValue().task(), offer.getKey(), amount);
        active.put(donor, delivery);
        return delivery;
      }
    }
    return null;
  }

  public synchronized int amount(Delivery d, long now) {
    if (!d.equals(active.get(d.donor())) || !fresh(d.donor(), now) || !fresh(d.recipient(), now))
      return 0;
    return Math.min(
        d.amount(),
        Math.min(
            workers.get(d.donor()).offer().getOrDefault(d.material(), 0),
            wanted(workers.get(d.recipient()), d.material())));
  }

  public synchronized void received(Delivery d, int amount) {
    Worker to = workers.get(d.recipient());
    if (to != null && to.task().equals(d.task())) {
      Map<String, Integer> needs = new HashMap<>(to.need());
      needs.replaceAll((m, n) -> matches(m, d.material()) ? Math.max(0, n - amount) : n);
      workers.put(
          d.recipient(), new Worker(to.position(), to.task(), needs, to.offer(), to.seen()));
    }
    active.remove(d.donor());
  }

  private boolean fresh(String id, long now) {
    Worker worker = workers.get(id);
    return worker != null && now - worker.seen() < 5000;
  }

  private static int wanted(Worker w, String material) {
    return w == null
        ? 0
        : w.need().entrySet().stream()
            .filter(e -> matches(e.getKey(), material))
            .mapToInt(Map.Entry::getValue)
            .max()
            .orElse(0);
  }

  public static boolean matches(String request, String material) {
    return request.equals(material)
        || request.equals("LOG") && material.endsWith("_LOG") && !material.startsWith("STRIPPED_");
  }

  public synchronized Map<String, ?> report() {
    return Map.of("workers", Map.copyOf(workers), "couriers", List.copyOf(active.values()));
  }
}
