package dev.coreai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PolicyProgramTest {
  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void executesBranchesAndArithmetic() {
    var program =
        PolicyProgram.compile("(if (> failures 1) (+ base (* failures 100)) (min base missing))");
    assertEquals(310, program.score(PolicyCase.features(10, "failures", 3)));
    assertEquals(2, program.score(PolicyCase.features(10, "missing", 2)));
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void rejectsHostAccessLoopsUnknownFieldsAndMalformedCode() {
    for (String code :
        List.of(
            "System.exit(0)",
            "(exec rm)",
            "(while 1 1)",
            "(read /etc/passwd)",
            "(class java.lang.Runtime)",
            "(if 1 2)",
            "(+ 1 2 3)",
            "NaN",
            "Infinity",
            "missing 2",
            "(/ 1 0)",
            "(unknown 1 2)"))
      assertThrows(IllegalArgumentException.class, () -> PolicyProgram.compile(code), code);
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void rejectsDeepAndLargePrograms() {
    assertThrows(
        IllegalArgumentException.class,
        () -> PolicyProgram.compile("(+ 1 ".repeat(18) + "1" + ")".repeat(18)));
    assertThrows(IllegalArgumentException.class, () -> PolicyProgram.compile("1".repeat(4097)));
    String tree = "1";
    for (int i = 0; i < 8; i++) tree = "(+ " + tree + " " + tree + ")";
    final String nodes = tree;
    assertThrows(IllegalArgumentException.class, () -> PolicyProgram.compile(nodes));
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void clampsArithmeticAndRejectsInvalidInputs() {
    assertEquals(
        1_000_000, PolicyProgram.compile("(* 1000000 1000000)").score(PolicyCase.features(0)));
    assertThrows(
        IllegalArgumentException.class, () -> PolicyProgram.compile("base").score(Map.of()));
    var invalid = new HashMap<>(PolicyCase.features(0));
    invalid.put("failures", Double.NaN);
    assertThrows(
        IllegalArgumentException.class, () -> PolicyProgram.compile("base").score(invalid));
  }

  @org.junit.jupiter.api.Tag("coreai")
  @Test
  void rankingTiesPreserveHostOrder() {
    var options =
        List.of(
            new PolicyCase.Option("b", PolicyCase.features(10)),
            new PolicyCase.Option("a", PolicyCase.features(10)));
    assertEquals("b", PolicyCase.best(PolicyProgram.compile("base"), options));
  }
}
