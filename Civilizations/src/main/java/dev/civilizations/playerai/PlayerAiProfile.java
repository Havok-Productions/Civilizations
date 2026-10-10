package dev.civilizations.playerai;

import java.util.Random;
import java.util.UUID;

/** Stable identity and generation seed, independent of an NPC's body or current goal. */
public record PlayerAiProfile(UUID id, long seed, String name) {
  public PlayerAiProfile {
    if (id == null || name == null || !name.matches("[A-Za-z][A-Za-z0-9_]{0,15}"))
      throw new IllegalArgumentException(
          "Use a name of 1-16 letters, digits or underscores, starting with a letter");
  }

  public static PlayerAiProfile generate(long seed, String requestedName) {
    var random = new Random(seed);
    String[] names = {"Ash", "Birch", "Rowan", "River", "Flint", "Fern", "Cedar", "Reed"};
    String name =
        requestedName == null
            ? names[random.nextInt(names.length)] + "_" + random.nextInt(1000, 10000)
            : requestedName;
    return new PlayerAiProfile(UUID.randomUUID(), seed, name);
  }
}
