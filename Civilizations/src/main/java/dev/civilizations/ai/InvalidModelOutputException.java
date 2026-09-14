package dev.civilizations.ai;

import java.io.IOException;

/**
 * Output may be retried once with reasoning disabled; transport failures are handled separately.
 */
public final class InvalidModelOutputException extends IOException {
  public InvalidModelOutputException(String message) {
    super(message);
  }
}
