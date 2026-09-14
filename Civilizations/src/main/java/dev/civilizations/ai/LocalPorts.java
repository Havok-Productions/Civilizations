package dev.civilizations.ai;

import java.io.IOException;
import java.net.*;
import java.util.function.IntPredicate;

/** Bounded alternative ports for a private managed runtime; never adopts a foreign process. */
public final class LocalPorts {
  private LocalPorts() {}

  public static int choose(int preferred, IntPredicate available) throws IOException {
    if (preferred < 1024 || preferred > 65535) throw new IOException("Invalid local port");
    for (int i = 0; i < 16; i++) {
      int candidate = 1024 + (preferred - 1024 + i) % (65536 - 1024);
      if (available.test(candidate)) return candidate;
    }
    throw new IOException("No free private runtime port within 16 candidates");
  }

  public static int find(int preferred) throws IOException {
    if (preferred < 1024 || preferred > 65535) throw new IOException("Invalid local port");
    try {
      return choose(
          preferred,
          port -> {
            try (ServerSocket socket = new ServerSocket()) {
              socket.setReuseAddress(false);
              socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
              return true;
            } catch (IOException e) {
              return false;
            }
          });
    } catch (IOException occupied) {
      // A crowded preferred range should not prevent an otherwise healthy local runtime.
      try (ServerSocket socket = new ServerSocket()) {
        socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        return socket.getLocalPort();
      }
    }
  }
}
