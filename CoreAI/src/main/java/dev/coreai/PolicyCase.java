package dev.coreai;

import java.util.*;

/** Host-labeled preference; replay agreement is not proof of counterfactual world success. */
public record PolicyCase(String id, String basis, List<Option> options, String preferred) {
  public record Option(String id, Map<String, Double> features) {
    public Option {
      features = Map.copyOf(features);
    }
  }

  public PolicyCase {
    options = List.copyOf(options);
  }

  public boolean passes(PolicyProgram program) {
    return best(program, options).equals(preferred);
  }

  public static String best(PolicyProgram program, List<Option> options) {
    return options.stream()
        .min(Comparator.comparingDouble(o -> program.score(o.features())))
        .orElseThrow()
        .id();
  }

  public static Map<String, Double> features(double base, Object... overrides) {
    Map<String, Double> f = new LinkedHashMap<>();
    PolicyProgram.FEATURES.stream().sorted().forEach(k -> f.put(k, 0.0));
    f.put("base", base);
    for (int i = 0; i < overrides.length; i += 2) {
      String name = (String) overrides[i];
      if (!f.containsKey(name)) throw new IllegalArgumentException(name);
      f.put(name, PolicyProgram.clamp(((Number) overrides[i + 1]).doubleValue()));
    }
    return Map.copyOf(f);
  }
}
