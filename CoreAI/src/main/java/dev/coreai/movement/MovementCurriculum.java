package dev.coreai.movement;

import java.util.*;

/** Approximate corridor simulator, explicitly NOT Minecraft's collision/velocity engine. */
public final class MovementCurriculum {
  public record Cell(
      double floor,
      double headroom,
      double width,
      boolean supported,
      boolean water,
      boolean known,
      boolean hazard) {}

  public record Course(String group, String family, List<Cell> cells) {}

  private MovementCurriculum() {}

  public static final List<String> FAMILIES =
      List.of(
          "flat",
          "stairs",
          "drops",
          "tight_passage",
          "gap",
          "water_exit",
          "fence",
          "unknown",
          "hazard",
          "changed_terrain");

  public static MovementSample run(Course course) {
    var cells = course.cells();
    double rise = 0,
        drop = 0,
        head = Double.POSITIVE_INFINITY,
        blocked = 0,
        support = 0,
        water = 0,
        unknown = 0,
        hazard = 0;
    boolean success = true;
    String reason = "virtual_arrival";
    for (int i = 0; i < cells.size(); i++) {
      var c = cells.get(i);
      if (i > 0) {
        double dy = c.floor() - cells.get(i - 1).floor();
        rise = Math.max(rise, dy);
        drop = Math.max(drop, -dy);
        if (dy > 1.0 || dy < -3.0) {
          success = false;
          reason = "unsupported_height_transition";
        }
      }
      head = Math.min(head, c.headroom());
      blocked += c.width() < .6 ? 1 : 0;
      support += c.supported() ? 1 : 0;
      water += c.water() ? 1 : 0;
      unknown += c.known() ? 0 : 1;
      hazard += c.hazard() ? 1 : 0;
      if (!c.known()
          || c.hazard()
          || c.headroom() < 1.95
          || c.width() < .6
          || !c.supported() && !c.water()
          || c.water() && !cells.getFirst().water()) {
        success = false;
        reason = "virtual_body_support_or_hazard_failure";
      }
    }
    double n = cells.size();
    return new MovementSample(
        course.group(),
        course.group(),
        course.family(),
        "virtual",
        List.of(
            n - 1, rise, drop, head, blocked / n, support / n, water / n, unknown / n, hazard / n),
        success,
        reason,
        0);
  }

  public static List<MovementSample> generate(long seed, int perFamily) {
    Random r = new Random(seed);
    List<MovementSample> samples = new ArrayList<>();
    for (String family : FAMILIES)
      for (int trial = 0; trial < perFamily; trial++) {
        List<Cell> cells = new ArrayList<>();
        int size = 3 + r.nextInt(5);
        double floor = 0;
        for (int i = 0; i < size; i++) {
          double head = 2 + r.nextDouble(), width = .65 + r.nextDouble();
          boolean supported = true, water = false, known = true, hazard = false;
          switch (family) {
            case "stairs" ->
                floor +=
                    i == 0
                        ? 0
                        : (trial % 2 == 0 ? .25 + r.nextDouble() * .75 : 1.1 + r.nextDouble());
            case "drops" ->
                floor -=
                    i == 0 ? 0 : (trial % 2 == 0 ? r.nextDouble() * 2.5 : 3.1 + r.nextDouble());
            case "tight_passage" -> {
              if (i == size / 2) head = trial % 2 == 0 ? 2 + r.nextDouble() : .8 + r.nextDouble();
            }
            case "gap", "changed_terrain" -> {
              if (i == size / 2) supported = trial % 2 == 0;
            }
            case "water_exit" -> {
              water = i < size - 1;
              supported = !water;
              if (i == size - 1) floor = trial % 2 == 0 ? .5 : 1.5;
            }
            case "fence" -> {
              if (i == size / 2) width = trial % 2 == 0 ? .65 : .2 + r.nextDouble() * .3;
            }
            case "unknown" -> {
              if (i == size / 2) known = trial % 2 == 0;
            }
            case "hazard" -> {
              if (i == size / 2) hazard = trial % 2 != 0;
            }
            default -> {}
          }
          cells.add(new Cell(floor, head, width, supported, water, known, hazard));
        }
        String group = family + ":" + seed + ":" + trial;
        samples.add(run(new Course(group, family, cells)));
        if (family.equals("changed_terrain") && trial % 2 != 0) {
          int i = size / 2;
          var c = cells.get(i);
          cells.set(
              i,
              new Cell(c.floor(), c.headroom(), c.width(), true, c.water(), c.known(), c.hazard()));
          var after = run(new Course(group, family, cells));
          samples.add(
              new MovementSample(
                  group + ":after",
                  group,
                  family,
                  "virtual",
                  after.features(),
                  after.success(),
                  "virtual_support_added",
                  0));
        }
      }
    return List.copyOf(samples);
  }
}
