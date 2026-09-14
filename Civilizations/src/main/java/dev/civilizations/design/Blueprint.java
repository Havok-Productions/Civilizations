package dev.civilizations.design;

import com.google.gson.*;
import java.util.*;

/** Declarative design language. Coordinates are offsets from the settlement center. */
public record Blueprint(
    String kind,
    String purpose,
    int x,
    int z,
    int width,
    int depth,
    int height,
    String direction,
    List<Point> points) {
  public record Point(int x, int z) {}

  /** Check finite geometry before allocating a terrain survey, even for distant proposals. */
  public void validateGeometry() {
    if (kind.equals("wall") || kind.equals("path")) RouteDesign.line(this, kind.equals("wall"));
    if (kind.equals("wall")) {
      java.math.BigInteger area = java.math.BigInteger.ZERO;
      for (int i = 0; i < points.size(); i++) {
        Point a = points.get(i), b = points.get((i + 1) % points.size());
        area =
            area.add(
                    java.math.BigInteger.valueOf(a.x())
                        .multiply(java.math.BigInteger.valueOf(b.z())))
                .subtract(
                    java.math.BigInteger.valueOf(b.x())
                        .multiply(java.math.BigInteger.valueOf(a.z())));
      }
      if (area.signum() == 0)
        throw new IllegalArgumentException(
            "Wall points form a line, not an enclosed area; provide polygon corners");
    }
  }

  public int surveyRadius() {
    long extent =
        Math.max(
            Math.abs((long) x) + Math.abs((long) width) + Math.abs((long) depth),
            Math.abs((long) z) + Math.abs((long) width) + Math.abs((long) depth));
    for (Point p : points)
      extent = Math.max(extent, Math.max(Math.abs((long) p.x()), Math.abs((long) p.z())));
    return (int) Math.min(Integer.MAX_VALUE, Math.max(32, extent + 3));
  }

  public static final String SCHEMA =
      """
      {"type":"object","properties":{"coordinate_space":{"type":"string","enum":["relative","world"]},"kind":{"type":"string","enum":["house","wall","path","farm","lights","mine","wait"]},"purpose":{"type":"string"},"x":{"type":"integer"},"z":{"type":"integer"},"width":{"type":"integer"},"depth":{"type":"integer"},"height":{"type":"integer"},"direction":{"type":"string","enum":["north","south","east","west"]},"points":{"type":"array","items":{"type":"object","properties":{"x":{"type":"integer"},"z":{"type":"integer"}},"required":["x","z"],"additionalProperties":false}}},"required":["coordinate_space","kind","purpose","x","z","width","depth","height","direction","points"],"additionalProperties":false}
      """;

  public static Blueprint parse(String text) {
    return parse(text, null);
  }

  public static Blueprint parse(String text, dev.civilizations.core.Pos origin) {
    if (text.length() > 16_000) throw new IllegalArgumentException("Design response too large");
    JsonObject j = JsonParser.parseString(text).getAsJsonObject();
    String coordinates =
        j.has("coordinate_space") ? j.remove("coordinate_space").getAsString() : "relative";
    if (!Set.of("relative", "world").contains(coordinates))
      throw new IllegalArgumentException("Unknown coordinate space");
    boolean absolute = coordinates.equals("world");
    if (absolute && origin == null)
      throw new IllegalArgumentException("World coordinates require a survey origin");
    int ox = absolute ? origin.x() : 0, oz = absolute ? origin.z() : 0;
    if (!j.keySet()
        .equals(
            Set.of("kind", "purpose", "x", "z", "width", "depth", "height", "direction", "points")))
      throw new IllegalArgumentException("Design fields do not match the supported language");
    String kind = j.get("kind").getAsString(),
        purpose = j.get("purpose").getAsString(),
        direction = j.get("direction").getAsString();
    if (!Set.of("house", "wall", "path", "farm", "lights", "mine", "wait").contains(kind)
        || !Set.of("north", "south", "east", "west").contains(direction))
      throw new IllegalArgumentException("Unsupported design kind/direction");
    if (purpose.isBlank() || purpose.length() > 240)
      throw new IllegalArgumentException("Give a short village need for the design");
    List<Point> points = new ArrayList<>();
    for (JsonElement e : j.getAsJsonArray("points")) {
      JsonObject p = e.getAsJsonObject();
      if (!p.keySet().equals(Set.of("x", "z"))) throw new IllegalArgumentException("Invalid point");
      points.add(
          new Point(
              Math.subtractExact(integer(p, "x"), ox), Math.subtractExact(integer(p, "z"), oz)));
    }
    return new Blueprint(
        kind,
        purpose,
        Math.subtractExact(integer(j, "x"), ox),
        Math.subtractExact(integer(j, "z"), oz),
        integer(j, "width"),
        integer(j, "depth"),
        integer(j, "height"),
        direction,
        List.copyOf(points));
  }

  private static int integer(JsonObject j, String key) {
    try {
      return j.get(key).getAsBigDecimal().intValueExact();
    } catch (Exception e) {
      throw new IllegalArgumentException(key + " must be an integer");
    }
  }
}
