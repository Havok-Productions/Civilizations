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
      {"type":"object","properties":{"kind":{"type":"string","enum":["house","wall","path","farm","lights","mine","wait"]},"purpose":{"type":"string"},"x":{"type":"integer"},"z":{"type":"integer"},"width":{"type":"integer"},"depth":{"type":"integer"},"height":{"type":"integer"},"direction":{"type":"string","enum":["north","south","east","west"]},"points":{"type":"array","items":{"type":"object","properties":{"x":{"type":"integer"},"z":{"type":"integer"}},"required":["x","z"],"additionalProperties":false}}},"required":["kind","purpose","x","z","width","depth","height","direction","points"],"additionalProperties":false}
      """;

  public static Blueprint parse(String text) {
    if (text.length() > 16_000) throw new IllegalArgumentException("Design response too large");
    JsonObject j = JsonParser.parseString(text).getAsJsonObject();
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
      points.add(new Point(integer(p, "x"), integer(p, "z")));
    }
    return new Blueprint(
        kind,
        purpose,
        integer(j, "x"),
        integer(j, "z"),
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
