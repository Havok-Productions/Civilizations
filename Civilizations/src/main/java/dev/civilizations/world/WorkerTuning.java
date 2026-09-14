package dev.civilizations.world;

import dev.civilizations.CivilizationsPlugin;
import dev.coreai.ParameterCatalog;
import java.util.*;
import org.bukkit.entity.Villager;

/** Host consumers share the same pilot-scoped values and expose the full catalog to proposals. */
public final class WorkerTuning {
  private WorkerTuning() {}

  public static int value(CivilizationsPlugin plugin, Villager actor, String key) {
    var spec = ParameterCatalog.SPECS.get(key);
    if (spec == null) throw new IllegalArgumentException("Unknown parameter: " + key);
    int fallback =
        key.equals("construction.interval_ms") ? (int) plugin.workMillis() : spec.defaultValue();
    return plugin.experiments() == null
        ? fallback
        : plugin.experiments().rules().parameter(actor.getUniqueId().toString(), key, fallback);
  }

  public static Map<String, Object> report(CivilizationsPlugin plugin, Villager actor) {
    var report = new TreeMap<String, Object>();
    ParameterCatalog.SPECS.forEach(
        (key, spec) ->
            report.put(key, Map.of("current", value(plugin, actor, key), "definition", spec)));
    return report;
  }
}
