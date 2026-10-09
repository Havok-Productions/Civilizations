package dev.coreai.movement;

import java.util.*;

/** Small CPU-only 9 -> 12 tanh -> 1 sigmoid network; no language-model dependency. */
public final class MovementNetwork {
  public static final int INPUTS = 9, HIDDEN = 12;

  public record Weights(int schema, double[][] hidden, double[] output) {}

  private final double[][] hidden;
  private final double[] output;

  public MovementNetwork(long seed) {
    hidden = new double[HIDDEN][INPUTS + 1];
    output = new double[HIDDEN + 1];
    Random random = new Random(seed);
    for (double[] row : hidden)
      for (int j = 0; j < INPUTS; j++) row[j] = random.nextGaussian() * .2;
    for (int j = 0; j < HIDDEN; j++) output[j] = random.nextGaussian() * .2;
  }

  public MovementNetwork(Weights weights) {
    if (weights == null
        || weights.schema() != 1
        || weights.hidden() == null
        || weights.hidden().length != HIDDEN
        || weights.output() == null
        || weights.output().length != HIDDEN + 1)
      throw new IllegalArgumentException("Invalid network dimensions");
    hidden = new double[HIDDEN][];
    for (int i = 0; i < HIDDEN; i++) {
      if (weights.hidden()[i] == null || weights.hidden()[i].length != INPUTS + 1)
        throw new IllegalArgumentException("Invalid hidden layer");
      hidden[i] = weights.hidden()[i].clone();
      for (double x : hidden[i])
        if (!Double.isFinite(x)) throw new IllegalArgumentException("Nonfinite weight");
    }
    output = weights.output().clone();
    for (double x : output)
      if (!Double.isFinite(x)) throw new IllegalArgumentException("Nonfinite output");
  }

  public Weights weights() {
    return new Weights(
        1, Arrays.stream(hidden).map(double[]::clone).toArray(double[][]::new), output.clone());
  }

  private double[] activate(double[] x) {
    double[] a = new double[HIDDEN];
    for (int i = 0; i < HIDDEN; i++) {
      double sum = hidden[i][INPUTS];
      for (int j = 0; j < INPUTS; j++) sum += hidden[i][j] * x[j];
      a[i] = Math.tanh(sum);
    }
    return a;
  }

  public double predict(MovementSample sample) {
    return output(activate(sample.inputs()));
  }

  private double output(double[] a) {
    double sum = output[HIDDEN];
    for (int i = 0; i < HIDDEN; i++) sum += output[i] * a[i];
    return 1 / (1 + Math.exp(-Math.clamp(sum, -40, 40)));
  }

  public void train(List<MovementSample> samples, int epochs, long seed) {
    if (samples.isEmpty() || samples.stream().anyMatch(s -> s.success() == null))
      throw new IllegalArgumentException("Training needs terminal labels");
    var order = new ArrayList<>(samples);
    Random random = new Random(seed);
    for (int epoch = 0; epoch < epochs; epoch++) {
      Collections.shuffle(order, random);
      double rate = .025 / (1 + epoch * .02);
      update(order, rate);
    }
  }

  /**
   * Rehearse every terrain family, giving each source, family and outcome equal exposure. Cycling
   * within each shuffled stratum retains earlier examples without giving common successful walks
   * more authority simply because they happen more often. Only training-partition examples belong
   * here; callers retain the untouched validation and test groups.
   */
  public void trainBalanced(List<MovementSample> samples, int epochs, long seed) {
    if (samples.isEmpty() || samples.stream().anyMatch(s -> s.success() == null))
      throw new IllegalArgumentException("Training needs terminal labels");
    var sources = new TreeMap<String, Map<String, Map<Boolean, List<MovementSample>>>>();
    for (var sample : samples)
      sources
          .computeIfAbsent(sample.source(), key -> new TreeMap<>())
          .computeIfAbsent(sample.family(), key -> new TreeMap<>())
          .computeIfAbsent(sample.success(), key -> new ArrayList<>())
          .add(sample);
    Random random = new Random(seed);
    Map<List<MovementSample>, Integer> offsets = new IdentityHashMap<>();
    for (var families : sources.values())
      for (var outcomes : families.values())
        for (var bucket : outcomes.values()) {
          // Input log order must not decide which old lessons survive rehearsal.
          bucket.sort(
              Comparator.comparing(MovementSample::group).thenComparing(MovementSample::id));
          Collections.shuffle(bucket, random);
          offsets.put(bucket, 0);
        }
    int draws = Math.max(256, samples.size() * 2);
    List<List<List<List<MovementSample>>>> sourceChoices = new ArrayList<>();
    for (var families : sources.values()) {
      List<List<List<MovementSample>>> familyChoices = new ArrayList<>();
      for (var outcomes : families.values()) familyChoices.add(new ArrayList<>(outcomes.values()));
      sourceChoices.add(familyChoices);
    }
    for (int epoch = 0; epoch < epochs; epoch++) {
      var replay = new ArrayList<MovementSample>(draws);
      for (int i = 0; i < draws; i++) {
        var families = sourceChoices.get(random.nextInt(sourceChoices.size()));
        var outcomes = families.get(random.nextInt(families.size()));
        var bucket = outcomes.get(random.nextInt(outcomes.size()));
        int offset = offsets.get(bucket);
        if (offset == bucket.size()) {
          Collections.shuffle(bucket, random);
          offset = 0;
        }
        replay.add(bucket.get(offset));
        offsets.put(bucket, offset + 1);
      }
      // Keep one continuous learning-rate schedule rather than restarting for each replay epoch.
      update(replay, .025 / (1 + epoch * .02));
    }
  }

  private void update(List<MovementSample> samples, double rate) {
    for (var sample : samples) {
      double[] x = sample.inputs(), a = activate(x);
      double error = output(a) - (sample.success() ? 1 : 0);
      for (int i = 0; i < HIDDEN; i++) {
        double gradient = error * output[i] * (1 - a[i] * a[i]);
        for (int j = 0; j < INPUTS; j++)
          hidden[i][j] -= rate * (gradient * x[j] + .0001 * hidden[i][j]);
        hidden[i][INPUTS] -= rate * gradient;
      }
      for (int i = 0; i < HIDDEN; i++) output[i] -= rate * (error * a[i] + .0001 * output[i]);
      output[HIDDEN] -= rate * error;
    }
  }
}
