package dev.civilizations.core;

public record Pos(int x, int y, int z) {
  public Pos add(int dx, int dy, int dz) {
    return new Pos(x + dx, y + dy, z + dz);
  }

  public long distance2(Pos p) {
    long dx = x - p.x, dy = y - p.y, dz = z - p.z;
    return dx * dx + dy * dy + dz * dz;
  }

  public long horizontal2(Pos p) {
    long dx = x - p.x, dz = z - p.z;
    return dx * dx + dz * dz;
  }

  public String key() {
    return x + "," + y + "," + z;
  }
}
