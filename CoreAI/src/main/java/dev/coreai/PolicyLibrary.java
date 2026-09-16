package dev.coreai;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Versioned expression programs; only the host evaluator can promote or roll them back. */
public final class PolicyLibrary {
  public record Provenance(
      String model, String artifact, String sha256, String license, String use) {}

  public record Version(String id, String source, Provenance teacher, long created) {}

  public record Evaluation(boolean accepted, int improvements, int regressions, String reason) {}

  public record Ranking(String version, List<PolicyCase.Option> options) {}

  private record Active(Version version, PolicyProgram program) {}

  private record State(
      Version active,
      List<Version> history,
      Set<String> rejected,
      int strikes,
      List<PolicyCase> replay,
      boolean liveValidated,
      Set<String> verified) {}

  private static final Gson JSON = new Gson();
  private final Path file;
  private final List<PolicyCase> guards;
  private final Deque<PolicyCase> replay = new ArrayDeque<>();
  private final List<Version> history = new ArrayList<>();
  private final Set<String> rejected = new LinkedHashSet<>();
  private volatile Active active = baseline();
  private int strikes;
  private boolean liveValidated;
  private final Set<String> verified = new HashSet<>(Set.of("baseline"));
  private Trial trial;

  private static final class Trial {
    final Active candidate;
    final long expires;
    String worker;
    int observations,
        improvements,
        regressions,
        comparisons,
        ignored,
        candidateFailures,
        controlFailures;
    final Map<String, ArrayDeque<PolicyMeasurement>> controls = new LinkedHashMap<>(),
        candidates = new LinkedHashMap<>();

    Trial(Active candidate) {
      this.candidate = candidate;
      expires = System.currentTimeMillis() + 300_000;
    }
  }

  public PolicyLibrary(Path folder, List<PolicyCase> guards) throws IOException {
    Files.createDirectories(folder);
    this.file = folder.resolve("state.json");
    this.guards = List.copyOf(guards);
    if (Files.exists(file)) {
      if (Files.size(file) > 512_000) throw new IOException("Policy state exceeds limit");
      try {
        State state = JSON.fromJson(Files.readString(file), State.class);
        if (state.history.size() > 32 || state.rejected.size() > 128)
          throw new IllegalArgumentException("History limit");
        if (state.replay.size() > 32) throw new IllegalArgumentException("Replay limit");
        state.replay.forEach(this::remember);
        history.addAll(state.history);
        rejected.addAll(state.rejected);
        strikes = state.strikes;
        PolicyProgram code = PolicyProgram.compile(state.active.source);
        if (state.active.id.equals("baseline") && !state.active.source.equals("base"))
          throw new IllegalArgumentException("Baseline source changed");
        if (!state.active.id.equals("baseline")
            && !state.active.id.equals(digest(state.active.source)))
          throw new IllegalArgumentException("Policy digest mismatch");
        if (!state.active.id.equals("baseline")
            && !state.liveValidated
            && !evaluate(code, baseline().program).accepted)
          throw new IllegalArgumentException("Policy no longer passes guards");
        liveValidated = state.liveValidated;
        if (state.verified != null) verified.addAll(state.verified);
        if (liveValidated) verified.add(state.active.id);
        active = new Active(state.active, code);
      } catch (RuntimeException e) {
        throw new IOException("Invalid saved policy; host must retain baseline", e);
      }
    }
  }

  private static Active baseline() {
    return new Active(
        new Version(
            "baseline", "base", new Provenance("host", "builtin", "", "project", "reference"), 0),
        PolicyProgram.compile("base"));
  }

  public Version version() {
    return active.version;
  }

  public Ranking rank(List<PolicyCase.Option> options) {
    if (options.size() > 16) throw new IllegalArgumentException("Candidate limit: 16");
    Active snap = active;
    List<PolicyCase.Option> sorted =
        options.stream()
            .sorted(Comparator.comparingDouble(o -> snap.program.score(o.features())))
            .toList();
    return new Ranking(snap.version.id, sorted);
  }

  /**
   * Only a different choice on one worker counts as an experiment. Host candidates remain
   * authoritative.
   */
  public synchronized Ranking rankForWorker(List<PolicyCase.Option> options, String worker) {
    Ranking incumbent = rank(options);
    if (trial == null) return incumbent;
    if (System.currentTimeMillis() > trial.expires) {
      trial = null;
      return incumbent;
    }
    if (trial.worker != null && !trial.worker.equals(worker)) return incumbent;
    List<PolicyCase.Option> sorted =
        options.stream()
            .sorted(Comparator.comparingDouble(o -> trial.candidate.program.score(o.features())))
            .toList();
    if (sorted.isEmpty() || sorted.getFirst().id().equals(incumbent.options.getFirst().id()))
      return incumbent;
    if (trial.worker == null) trial.worker = worker;
    return trial.observations % 2 == 0
        ? new Ranking("control:" + trial.candidate.version.id, incumbent.options)
        : new Ranking("trial:" + trial.candidate.version.id, sorted);
  }

  public synchronized Evaluation stageTrial(String source, Provenance teacher) {
    PolicyProgram code = PolicyProgram.compile(source);
    String id = digest(source);
    if (trial != null && System.currentTimeMillis() <= trial.expires)
      return new Evaluation(false, 0, 0, "Another candidate is awaiting live evidence");
    if (source.equals(active.version.source)
        || id.equals(active.version.id)
        || rejected.contains(id))
      return new Evaluation(false, 0, 0, "Unchanged or previously rolled-back candidate");
    Evaluation replay = evaluate(code, active.program);
    trial =
        new Trial(new Active(new Version(id, source, teacher, System.currentTimeMillis()), code));
    return new Evaluation(
        true,
        replay.improvements,
        replay.regressions,
        "One-worker comparison staged; alternate incumbent and candidate on comparable work; not"
            + " adopted");
  }

  public synchronized String trialOutcome(
      String version, String worker, PolicyMeasurement measurement) throws IOException {
    if (trial != null && System.currentTimeMillis() > trial.expires) trial = null;
    if (trial == null
        || !(version.equals("trial:" + trial.candidate.version.id)
            || version.equals("control:" + trial.candidate.version.id))
        || !Objects.equals(worker, trial.worker)) return "";
    trial.observations++;
    if (!measurement.relevant()) {
      trial.ignored++;
      return "live_trial_inconclusive: " + measurement.reason();
    }
    boolean controlArm = version.startsWith("control:");
    if (!measurement.success()) {
      if (controlArm) trial.controlFailures++;
      else {
        trial.candidateFailures++;
        trial.regressions++;
        if (trial.regressions >= 3) {
          trial = null;
          return "live_trial_suspended_after_attributed_failures; candidate may be revised or"
                     + " retried";
        }
      }
    }
    var arm = controlArm ? trial.controls : trial.candidates;
    arm.computeIfAbsent(measurement.context(), k -> new ArrayDeque<>()).addLast(measurement);
    while (arm.size() > 32) arm.remove(arm.keySet().iterator().next());
    while (arm.getOrDefault(measurement.context(), new ArrayDeque<>()).size() > 8)
      arm.get(measurement.context()).removeFirst();
    var controls = trial.controls.get(measurement.context());
    var candidates = trial.candidates.get(measurement.context());
    if (controls == null || controls.isEmpty() || candidates == null || candidates.isEmpty())
      return "live_trial_waiting_for_matching_comparison";
    PolicyMeasurement control = controls.removeFirst(), candidateResult = candidates.removeFirst();
    trial.comparisons++;
    boolean better =
        candidateResult.success()
            && (!control.success() || candidateResult.cost() < control.cost() * .9);
    boolean worse =
        candidateResult.success()
            && control.success()
            && candidateResult.cost() > control.cost() * 1.1;
    if (better) trial.improvements++;
    if (worse) trial.regressions++;
    if (trial.regressions >= 3) {
      trial = null;
      return "live_trial_suspended_after_measured_regressions; candidate may be revised or retried";
    }
    if (trial.improvements < 3 || trial.regressions > 0)
      return better
          ? "live_trial_pair_improved"
          : worse ? "live_trial_pair_regressed" : "live_trial_pair_inconclusive";
    Active candidate = trial.candidate;
    List<Version> nextHistory = new ArrayList<>(history);
    nextHistory.add(active.version);
    if (nextHistory.size() > 32) nextHistory.removeFirst();
    write(
        new State(
            candidate.version,
            List.copyOf(nextHistory),
            Set.copyOf(rejected),
            0,
            List.copyOf(replay),
            true,
            withVerified(candidate.version.id)));
    history.clear();
    history.addAll(nextHistory);
    active = candidate;
    verified.add(candidate.version.id);
    liveValidated = true;
    strikes = 0;
    trial = null;
    return "live_trial_adopted_after_three_measured_improvements";
  }

  public synchronized String trialStatus() {
    return trial == null
        ? "none"
        : "candidate="
            + trial.candidate.version.id.substring(0, 12)
            + ", worker="
            + trial.worker
            + ", comparisons="
            + trial.comparisons
            + ", improvements="
            + trial.improvements
            + ", regressions="
            + trial.regressions
            + ", candidate_failures="
            + trial.candidateFailures
            + ", control_failures="
            + trial.controlFailures
            + ", inconclusive="
            + trial.ignored;
  }

  public synchronized List<PolicyCase> cases() {
    List<PolicyCase> all = new ArrayList<>(guards);
    all.addAll(replay);
    return List.copyOf(all);
  }

  public synchronized List<Map<String, Object>> report() {
    PolicyProgram code = active.program;
    return cases().stream()
        .map(
            sample -> {
              var compact =
                  sample.options().stream()
                      .map(
                          option -> {
                            Map<String, Double> nonzero = new TreeMap<>();
                            option
                                .features()
                                .forEach(
                                    (k, v) -> {
                                      if (v != 0 || k.equals("base")) nonzero.put(k, v);
                                    });
                            return Map.of("id", option.id(), "features", nonzero);
                          })
                      .toList();
              return Map.<String, Object>of(
                  "id",
                  sample.id(),
                  "basis",
                  sample.basis(),
                  "options",
                  compact,
                  "preferred",
                  sample.preferred(),
                  "current_choice",
                  PolicyCase.best(code, sample.options()),
                  "passing",
                  sample.passes(code));
            })
        .toList();
  }

  public synchronized void remember(PolicyCase sample) {
    if (sample.options().size() < 2
        || sample.options().size() > 16
        || sample.options().stream().noneMatch(o -> o.id().equals(sample.preferred()))) return;
    sample.options().forEach(o -> baseline().program.score(o.features()));
    if (replay.size() == 32) replay.removeFirst();
    replay.addLast(sample);
  }

  private Evaluation evaluate(PolicyProgram candidate, PolicyProgram incumbent) {
    int better = 0, worse = 0;
    for (PolicyCase sample : cases()) {
      boolean old = sample.passes(incumbent), next = sample.passes(candidate);
      if (old && !next) worse++;
      if (!old && next) better++;
    }
    return new Evaluation(
        worse == 0 && better > 0,
        better,
        worse,
        worse > 0
            ? "Regression in host-labeled replay"
            : better == 0 ? "No measured replay improvement" : "Replay improved; live probation");
  }

  public synchronized Evaluation propose(String source, Provenance teacher) throws IOException {
    PolicyProgram candidate = PolicyProgram.compile(source);
    String id = digest(source);
    if (rejected.contains(id)) return new Evaluation(false, 0, 0, "Previously rolled back");
    Evaluation result = evaluate(candidate, active.program);
    if (result.accepted) {
      Active previous = active;
      history.add(previous.version);
      if (history.size() > 32) history.removeFirst();
      Version version = new Version(id, source, teacher, System.currentTimeMillis());
      // Publish only after persistence succeeds. No candidate can supply a path or execute host IO.
      write(
          new State(
              version,
              List.copyOf(history),
              Set.copyOf(rejected),
              0,
              List.copyOf(replay),
              false,
              Set.copyOf(verified)));
      strikes = 0;
      liveValidated = false;
      trial = null;
      active = new Active(version, candidate);
    }
    return result;
  }

  public synchronized boolean outcome(String version, boolean success) throws IOException {
    if (active.version.id.equals("baseline") || !active.version.id.equals(version)) return false;
    strikes = success ? 0 : strikes + 1;
    if (strikes < 3) {
      write(
          new State(
              active.version,
              List.copyOf(history),
              Set.copyOf(rejected),
              strikes,
              List.copyOf(replay),
              liveValidated,
              success ? withVerified(active.version.id) : Set.copyOf(verified)));
      if (success) verified.add(active.version.id);
      return false;
    }
    rollback();
    return true;
  }

  public synchronized void rollback() throws IOException {
    Active old = active;
    Set<String> rejectedNext = new LinkedHashSet<>(rejected);
    if (!old.version.id.equals("baseline")) rejectedNext.add(old.version.id);
    while (rejectedNext.size() > 128) rejectedNext.remove(rejectedNext.iterator().next());
    Active restored = baseline();
    for (int i = history.size() - 1; i >= 0; i--) {
      Version previous = history.get(i);
      if (!previous.id.equals(old.version.id)
          && verified.contains(previous.id)
          && !rejectedNext.contains(previous.id)) {
        restored = new Active(previous, PolicyProgram.compile(previous.source));
        break;
      }
    }
    write(
        new State(
            restored.version,
            List.copyOf(history),
            Set.copyOf(rejectedNext),
            0,
            List.copyOf(replay),
            !restored.version.id.equals("baseline"),
            Set.copyOf(verified)));
    active = restored;
    rejected.clear();
    rejected.addAll(rejectedNext);
    trial = null;
    liveValidated = !restored.version.id.equals("baseline");
    strikes = 0;
  }

  private Set<String> withVerified(String id) {
    Set<String> result = new HashSet<>(verified);
    result.add(id);
    // Keep only the live history; the state file remains bounded.
    result.removeIf(
        v ->
            !v.equals("baseline")
                && !v.equals(id)
                && !v.equals(active.version.id)
                && history.stream().noneMatch(h -> h.id.equals(v)));
    return Set.copyOf(result);
  }

  private void write(State state) throws IOException {
    Path temporary = file.resolveSibling("state.tmp");
    String json = JSON.toJson(state);
    if (json.length() > 500_000) throw new IOException("Policy state size limit");
    Files.writeString(temporary, json);
    try {
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static String digest(String source) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
