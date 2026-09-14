package dev.civilizations.core;

import java.util.Locale;
import java.util.Map;

/**
 * Routes failure-bearing events to a separate bounded journal, including disabled routine debug.
 */
public final class FailureEvents {
  private FailureEvents() {}

  public static boolean isFailure(String type, Map<String, ?> data) {
    if (type.contains("failure") || type.equals("exception") || data.containsKey("error"))
      return true;
    if (Boolean.FALSE.equals(data.get("success")) || Boolean.FALSE.equals(data.get("verified")))
      return true;
    for (String field : new String[] {"status", "stage", "result", "reason", "event", "message"}) {
      String value = String.valueOf(data.get(field)).toLowerCase(Locale.ROOT);
      if (value.contains("fail")
          || value.contains("reject")
          || value.contains("blocked")
          || value.contains("timeout")
          || value.contains("cancel")
          || value.contains("unavailable")
          || value.contains("no ")
          || value.contains("cannot")
          || value.contains("invalid")) return true;
    }
    return false;
  }
}
