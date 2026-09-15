package test;

/** Physical checks are opt-in and exclusive; no implicit full-village scenario. */
enum Scenario {
  COOPERATION,
  PROGRESS,
  LIVE_SKILL,
  RULE_LEARNING,
  NAVIGATION,
  CRAFTING,
  REPAIR,
  AUTONOMY,
  INFERENCE_BUSY,
  WORKFLOW,
  OBSERVATIONS,
  CONNECTIONS,
  DISCOVERY,
  MANUAL_VILLAGE;

  static Scenario selected() {
    String value = System.getProperty("civilizations.test.scenario", "");
    if (value.isBlank())
      throw new IllegalArgumentException(
          "Select exactly one -Dcivilizations.test.scenario=<name>; see TESTING.md. Legacy mixed"
              + " flags are retired.");
    return valueOf(value.toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
  }
}
