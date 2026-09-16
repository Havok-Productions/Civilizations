package dev.civilizations;

import static org.junit.jupiter.api.Assertions.*;

import dev.civilizations.core.*;
import java.util.*;
import org.junit.jupiter.api.*;

class SourceRejectionsTest {
  @Test
  @Tag("navigation")
  @Tag("crafting")
  @Tag("diagnostics")
  @Tag("interaction")
  void repeatedWetSourceIsSkippedButChangedTerrainAndOtherWorkersCanTryIt() {
    Pos wet = new Pos(1, 65, 0), dry = new Pos(3, 65, 0);
    var memory = new SourceRejections();
    memory.reject("SAND", wet, "sand;water-east", "would_expose_fluid");
    for (int scan = 0; scan < 5; scan++)
      assertEquals(
          List.of(dry),
          List.of(wet, dry).stream()
              .filter(
                  p ->
                      !memory.unchanged(
                          "SAND", p, () -> p.equals(wet) ? "sand;water-east" : "sand;air-east"))
              .toList());
    assertFalse(new SourceRejections().unchanged("SAND", wet, () -> "sand;water-east"));
    assertFalse(memory.unchanged("SAND", wet, () -> null));
    assertTrue(memory.unchanged("SAND", wet, () -> "sand;water-east"));
    assertFalse(memory.unchanged("SAND", wet, () -> "sand;air-east"));
    assertTrue(memory.reasons().isEmpty());
  }
}
