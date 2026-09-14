package dev.coreai;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Editable environment rules. Probes test hypotheses; only a completed live trial publishes them.
 */
public final class TerrainRuleBook {
  public record Facts(
      String material,
      String state,
      boolean observed,
      boolean passable,
      boolean solid,
      boolean removable,
      boolean fluid,
      boolean dangerous,
      boolean container) {}

  public record Rule(
      Facts facts, String category, String explanation, String teacher, long learnedAt) {}

  public record State(List<Rule> rules, int searchRadius, Map<String, Integer> parameters) {
    public State {
      parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public State(List<Rule> rules, int searchRadius) {
      this(rules, searchRadius, Map.of());
    }
  }

  private static final class Pilot {
    final String worker;
    final Map<String, Rule> rules = new LinkedHashMap<>();
    int radius;
    final Map<String, Integer> parameters = new LinkedHashMap<>();

    Pilot(String worker) {
      this.worker = worker;
    }
  }

  private final Path file;
  private final LinkedHashMap<String, Rule> rules = new LinkedHashMap<>();
  private final Map<String, Pilot> pilots = new HashMap<>();
  private int searchRadius;
  private final Map<String, Integer> parameters = new LinkedHashMap<>();

  public TerrainRuleBook(Path root) throws IOException {
    Files.createDirectories(root);
    file = root.resolve("terrain.json");
    if (Files.exists(file)) {
      if (Files.size(file) > 1_000_000) throw new IOException("Terrain rule size limit");
      try {
        State saved = new Gson().fromJson(Files.readString(file), State.class);
        if (saved.rules().size() > 256) throw new IllegalArgumentException("Rule storage capacity");
        for (Rule r : saved.rules()) {
          validate(r.facts(), r.category());
          rules.put(key(r.facts().material(), r.facts().state()), r);
        }
        saved.parameters().forEach(ParameterCatalog::validate);
        parameters.putAll(saved.parameters());
        searchRadius = saved.searchRadius();
      } catch (RuntimeException e) {
        throw new IOException("Invalid terrain rules", e);
      }
    }
  }

  public static void validate(Facts facts, String category) {
    if (!Set.of("PASSABLE", "CLEARABLE", "OBSTACLE").contains(category))
      throw new IllegalArgumentException("Unknown classification");
    if (!facts.observed() || facts.material().equals("UNKNOWN"))
      throw new IllegalArgumentException("Unobserved block cannot be classified");
    if (facts.dangerous() || facts.fluid())
      throw new IllegalArgumentException("Measured danger/fluid cannot be relabeled as harmless");
    if (category.equals("PASSABLE") && !facts.passable())
      throw new IllegalArgumentException("Classification contradicts observed collision");
    if (category.equals("CLEARABLE") && (!facts.removable() || facts.solid() || facts.container()))
      throw new IllegalArgumentException("No evidence for nonstructural removal");
  }

  public static String key(String material, String state) {
    return material + ":" + state;
  }

  public synchronized Rule rule(String worker, String material, String state) {
    String key = key(material, state);
    for (Pilot p : pilots.values())
      if (p.worker.equals(worker) && p.rules.containsKey(key)) return p.rules.get(key);
    return rules.get(key);
  }

  public synchronized Rule stage(
      String trial,
      String worker,
      Facts facts,
      String category,
      String explanation,
      String teacher) {
    validate(facts, category);
    Pilot pilot = pilot(trial, worker);

    Rule r = new Rule(facts, category, explanation, teacher, System.currentTimeMillis());
    pilot.rules.put(key(facts.material(), facts.state()), r);
    return r;
  }

  private Pilot pilot(String trial, String worker) {
    if (!pilots.containsKey(trial) && pilots.size() >= 4)
      throw new IllegalArgumentException("Too many rule pilots");
    Pilot p = pilots.computeIfAbsent(trial, k -> new Pilot(worker));
    if (!p.worker.equals(worker)) throw new IllegalArgumentException("Trial worker changed");
    return p;
  }

  public synchronized void stageRadius(String trial, String worker, int radius) {
    pilot(trial, worker).radius = radius;
  }

  public synchronized int radius(String worker, int fallback, int maximum) {
    int result = searchRadius == 0 ? fallback : searchRadius;
    for (Pilot p : pilots.values()) if (p.worker.equals(worker) && p.radius != 0) result = p.radius;
    return result;
  }

  public synchronized void stageParameter(String trial, String worker, String key, int value) {
    ParameterCatalog.validate(key, value);
    pilot(trial, worker).parameters.put(key, value);
  }

  public synchronized int parameter(String worker, String key, int fallback) {
    if (!ParameterCatalog.SPECS.containsKey(key))
      throw new IllegalArgumentException("Unknown parameter");
    int value = parameters.getOrDefault(key, fallback);
    for (Pilot p : pilots.values())
      if (p.worker.equals(worker)) value = p.parameters.getOrDefault(key, value);
    return value;
  }

  public synchronized void cancel(String trial) {
    pilots.remove(trial);
  }

  /** Called only on the learning IO thread. Failed/interrupted candidates do not become rules. */
  public State finish(String trial, boolean success) throws IOException {
    State state;
    synchronized (this) {
      Pilot p = pilots.get(trial);
      if (p == null || !success) {
        pilots.remove(trial);
        return snapshot();
      }
      var next = new LinkedHashMap<>(rules);
      p.rules.forEach(
          (key, value) -> {
            next.remove(key);
            next.put(key, value);
          });
      while (next.size() > 256) next.remove(next.keySet().iterator().next());
      var nextParameters = new LinkedHashMap<>(parameters);
      nextParameters.putAll(p.parameters);
      state =
          new State(
              List.copyOf(next.values()), p.radius == 0 ? searchRadius : p.radius, nextParameters);
      while (new Gson().toJson(state).length() > 900000 && next.size() > 1) {
        next.remove(next.keySet().iterator().next());
        state = new State(List.copyOf(next.values()), state.searchRadius(), state.parameters());
      }
    }
    // Disk IO never holds the lock used by region-thread probes.
    try {
      write(state);
    } catch (IOException error) {
      cancel(trial);
      throw error;
    }
    synchronized (this) {
      pilots.remove(trial);
      rules.clear();
      for (Rule r : state.rules()) rules.put(key(r.facts().material(), r.facts().state()), r);
      parameters.clear();
      parameters.putAll(state.parameters());
      searchRadius = state.searchRadius();
    }
    return state;
  }

  private void write(State state) throws IOException {
    Path tmp = file.resolveSibling("terrain.tmp");
    Files.writeString(tmp, new Gson().toJson(state));
    try {
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  public synchronized Map<String, Rule> view(String worker) {
    var result = new LinkedHashMap<>(rules);
    for (Pilot p : pilots.values()) if (p.worker.equals(worker)) result.putAll(p.rules);
    return Map.copyOf(result);
  }

  public synchronized State snapshot() {
    return new State(List.copyOf(rules.values()), searchRadius, parameters);
  }
}
