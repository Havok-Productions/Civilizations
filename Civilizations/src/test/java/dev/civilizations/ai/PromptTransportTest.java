package dev.civilizations.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PromptTransportTest {
  @TempDir Path root;

  @Test
  @Tag("runtime")
  @Tag("inference")
  @Tag("interaction")
  void managedRuntimeCountsTheActualTemplateBeforeGenerationWithoutStartingAModel()
      throws Exception {
    var paths = new CopyOnWriteArrayList<String>();
    var bodies = new CopyOnWriteArrayList<JsonObject>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          paths.add(path);
          bodies.add(
              JsonParser.parseString(
                      new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                  .getAsJsonObject());
          String answer =
              switch (path) {
                case "/apply-template" -> "{\"prompt\":\"templated system and user\"}";
                case "/tokenize" -> "{\"tokens\":[1,2,3,4,5]}";
                default ->
                    "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"validated\"}}]}";
              };
          byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    var logs = new ArrayList<String>();
    try (var runtime =
        new LocalRuntime(LocalRuntime.Settings.read(new YamlConfiguration()), root, logs::add)) {
      // Inject an already-ready transport; no download, server process or model inference is
      // started.
      var ready = LocalRuntime.class.getDeclaredField("ready");
      ready.setAccessible(true);
      ready.set(runtime, true);
      var endpoint = LocalRuntime.class.getDeclaredField("endpoint");
      endpoint.setAccessible(true);
      endpoint.set(runtime, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
      assertEquals(
          "validated",
          runtime.complete(
              "system",
              "observation",
              ReasoningMode.DESIGN,
              "{\"type\":\"object\"}",
              System.currentTimeMillis() + 5000));
      assertEquals(List.of("/apply-template", "/tokenize", "/v1/chat/completions"), paths);
      assertEquals(bodies.get(0).get("messages"), bodies.get(2).get("messages"));
      assertEquals(bodies.get(0).get("response_format"), bodies.get(2).get("response_format"));
      assertEquals("templated system and user", bodies.get(1).get("content").getAsString());
      assertTrue(
          logs.stream()
              .anyMatch(s -> s.contains("prompt_tokens=5") && s.contains("output_reserve=2048")));
    } finally {
      server.stop(0);
    }
  }
}
