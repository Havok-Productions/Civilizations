package dev.civilizations.core;

import java.util.Locale;

/** Common recovery category for executor and navigation diagnostics. */
public enum WorkFailure {
  INVENTORY_CAPACITY,
  OTHER;

  public static WorkFailure classify(String reason) {
    String value = reason.toLowerCase(Locale.ROOT).replace('_', ' ');
    return value.contains("inventory full")
            || value.contains("no room for")
            || value.contains("needs inventory space")
            || value.contains("no inventory space")
        ? INVENTORY_CAPACITY
        : OTHER;
  }
}
