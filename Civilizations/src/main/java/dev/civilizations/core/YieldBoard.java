package dev.civilizations.core;

import java.util.*;

/** Short-lived requests; the recipient alone controls its movement on its entity scheduler. */
public final class YieldBoard {
  public record Request(String requester, String task, List<Pos> spaces, long expires) {
    public Request {
      spaces = List.copyOf(spaces);
    }
  }

  private final Map<String, Request> requests = new HashMap<>();

  public synchronized void request(String recipient, Request request, long now) {
    requests.entrySet().removeIf(e -> e.getValue().expires() <= now);
    if (recipient.equals(request.requester())) return;
    var reciprocal = requests.get(request.requester());
    if (reciprocal != null && reciprocal.requester().equals(recipient)) {
      if (request.requester().compareTo(recipient) > 0) return;
      requests.remove(request.requester());
    }
    var previous = requests.get(recipient);
    if (previous == null
        || previous.requester().equals(request.requester())
        || request.requester().compareTo(previous.requester()) < 0)
      requests.put(recipient, request);
  }

  public synchronized Request incoming(String recipient, long now) {
    var result = requests.get(recipient);
    if (result != null && result.expires() <= now) {
      requests.remove(recipient);
      return null;
    }
    return result;
  }

  public synchronized void cancel(String requester) {
    requests.values().removeIf(r -> r.requester().equals(requester));
  }
}
