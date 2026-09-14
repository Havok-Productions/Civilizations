package dev.civilizations.ai;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.zip.*;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.bukkit.configuration.file.FileConfiguration;

/** All download/inference/process work runs off Minecraft threads. */
public final class LocalRuntime implements ModelBackend {
  public record Settings(
      boolean enabled,
      String backend,
      String endpoint,
      String modelId,
      int timeout,
      int tokens,
      int thinkingTokens,
      int thinkingTimeout,
      int reasoningBudget,
      boolean thinking,
      boolean thinkingOption,
      double temperature,
      String release,
      String accelerator,
      int port,
      int threads,
      int context,
      int startupTimeout,
      String modelUrl,
      String modelFile,
      String modelHash,
      long modelBytes) {
    public static Settings read(FileConfiguration c) {
      return read(c, "ai");
    }

    public static Settings read(FileConfiguration c, String prefix) {
      return new Settings(
          c.getBoolean(prefix + ".enabled", true),
          c.getString(prefix + ".backend", "managed"),
          c.getString(prefix + ".endpoint", "http://127.0.0.1:8642/v1"),
          c.getString(prefix + ".model-id", "civilizations-local"),
          Math.clamp(c.getInt(prefix + ".timeout-seconds", 45), 5, 180),
          Math.clamp(c.getInt(prefix + ".max-output-tokens", 180), 64, 1024),
          Math.clamp(c.getInt(prefix + ".thinking-output-tokens", 2048), 256, 8192),
          Math.clamp(c.getInt(prefix + ".thinking-timeout-seconds", 60), 10, 180),
          Math.clamp(c.getInt(prefix + ".reasoning-budget-tokens", 768), 0, 4096),
          c.getBoolean(prefix + ".thinking", false),
          c.getBoolean(prefix + ".model.supports-thinking-option", true),
          c.getDouble(prefix + ".temperature", 0.2),
          c.getString(prefix + ".runtime.release", "b10948"),
          c.getString(prefix + ".runtime.accelerator", "auto"),
          c.getInt(prefix + ".runtime.port", 8642),
          Math.clamp(c.getInt(prefix + ".runtime.threads", 4), 1, 32),
          Math.clamp(c.getInt(prefix + ".runtime.context-size", 8192), 2048, 32768),
          Math.clamp(c.getInt(prefix + ".runtime.startup-timeout-seconds", 240), 30, 600),
          c.getString(prefix + ".model.url"),
          c.getString(prefix + ".model.file"),
          c.getString(prefix + ".model.sha256"),
          c.getLong(prefix + ".model.bytes"));
    }
  }

  private final Settings s;
  private final Path root;
  private final Consumer<String> log;
  private final HttpClient http =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NORMAL)
          .connectTimeout(Duration.ofSeconds(20))
          .build();
  private final ScheduledExecutorService lifecycle =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "Civilizations-local-runtime");
            t.setDaemon(true);
            return t;
          });
  private volatile Process process;
  private RuntimeOwnership ownership;
  private volatile boolean closed, ready;
  private volatile String status = "not started";
  private final String key = UUID.randomUUID().toString();
  private volatile String endpoint;
  private final RuntimeRecovery recovery = new RuntimeRecovery();
  private final Thread shutdownHook = new Thread(this::close, "Civilizations-runtime-shutdown");
  private boolean started;
  private int runtimePort;

  public LocalRuntime(Settings settings, Path root, Consumer<String> log) {
    this.s = settings;
    this.root = root;
    this.log = log;
  }

  public synchronized void start() {
    if (started || closed) return;
    started = true;
    if (!s.enabled) {
      status = "disabled; local rules active";
      return;
    }
    Runtime.getRuntime().addShutdownHook(shutdownHook);
    lifecycle.execute(this::boot);
  }

  private synchronized void state(String text) {
    if (closed) return;
    status = text;
    log.accept("Local AI: " + text);
  }

  private void boot() {
    if (closed) return;
    try {
      if (!s.backend.equals("managed") && !s.backend.equals("endpoint"))
        throw new IOException("backend must be managed or endpoint");
      if (s.backend.equals("endpoint")) {
        endpoint = localEndpoint(s.endpoint);
        ready = health();
        state(ready ? "ready (local endpoint)" : "local endpoint unavailable");
      } else {
        if (!s.release.matches("b[0-9]+")) throw new IOException("Pin a llama.cpp bNNNN release");
        if (!Set.of("auto", "cpu", "vulkan", "cuda").contains(s.accelerator))
          throw new IOException("accelerator must be auto, cpu, vulkan or cuda");
        if (s.port < 1024 || s.port > 65535) throw new IOException("Invalid local port");
        Files.createDirectories(root);
        if (ownership == null) {
          RuntimeOwnership created = new RuntimeOwnership(root, this::state);
          synchronized (this) {
            if (closed) {
              created.close();
              throw new InterruptedException("Runtime closing");
            }
            ownership = created;
          }
        }
        Path model = root.resolve("models").resolve(safeFile(s.modelFile));
        state("verifying/downloading model " + s.modelFile);
        download(s.modelUrl, model, s.modelHash, s.modelBytes);
        String os = os(), arch = arch();
        String accelerator =
            s.accelerator.equals("auto")
                ? (arch.equals("x64") && !os.equals("macos") ? "vulkan" : "cpu")
                : s.accelerator;
        try {
          launch(install(os, arch, accelerator), model, accelerator);
        } catch (Exception e) {
          kill();
          if (closed || e instanceof InterruptedException) throw e;
          if (!s.accelerator.equals("auto") || accelerator.equals("cpu")) throw e;
          state("GPU startup unavailable; retrying CPU: " + e.getMessage());
          launch(install(os, arch, "cpu"), model, "cpu");
        }
        ready = true;
        state("ready: " + s.modelFile + " on " + endpoint);
      }
    } catch (Exception e) {
      ready = false;
      kill();
      retry("failed: " + e.getMessage());
      return;
    }
    schedule(this::monitor, 10);
  }

  private synchronized void schedule(Runnable task, long seconds) {
    if (!closed) lifecycle.schedule(task, seconds, TimeUnit.SECONDS);
  }

  private void retry(String reason) {
    if (closed) return;
    long delay = recovery.retrySeconds();
    state(reason + "; retrying in " + delay + "s; villagers continue using local rules");
    schedule(this::boot, delay);
  }

  private void monitor() {
    if (closed) return;
    if (s.backend.equals("endpoint")) {
      boolean ok = health();
      if (ok != ready) {
        ready = ok;
        state(ok ? "ready (local endpoint)" : "local endpoint unavailable");
      }
    } else {
      Process current = process;
      if (current == null || !current.isAlive()) {
        ready = false;
        String reason =
            current == null ? "runtime missing" : "runtime exited (" + current.exitValue() + ")";
        kill();
        retry(reason);
        return;
      }
      boolean ok = health();
      if (recovery.restartForHealth(ok, System.nanoTime())) {
        ready = false;
        kill();
        retry("runtime failed three consecutive health checks");
        return;
      }
      if (ok != ready) {
        ready = ok;
        state(
            ok
                ? "ready: " + s.modelFile + " on " + endpoint
                : "runtime health check failed; checking again in 10s");
      }
    }
    schedule(this::monitor, 10);
  }

  public static String localEndpoint(String value) {
    URI u = URI.create(value);
    String host = u.getHost();
    if (!"http".equals(u.getScheme())
        || !Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(host == null ? "" : host)
        || u.getUserInfo() != null
        || u.getQuery() != null)
      throw new IllegalArgumentException("Endpoint must be an HTTP loopback address");
    return value.replaceAll("/+$", "");
  }

  private Path install(String os, String arch, String accelerator) throws Exception {
    String suffix;
    if (os.equals("win"))
      suffix =
          "win-" + (accelerator.equals("cuda") ? "cuda-13.3" : accelerator) + "-" + arch + ".zip";
    else if (os.equals("macos")) suffix = "macos-" + arch + ".tar.gz";
    else {
      if (accelerator.equals("cuda"))
        throw new IOException(
            "Managed CUDA currently supports Windows x64; choose auto/Vulkan, CPU or a local"
                + " endpoint on Linux");
      suffix = "ubuntu-" + (accelerator.equals("cpu") ? "" : accelerator + "-") + arch + ".tar.gz";
    }
    Path dir =
        root.resolve("runtime").resolve(s.release + "-" + os + "-" + arch + "-" + accelerator);
    if (Files.exists(dir.resolve("installed.ok"))) {
      Path exe = findExe(dir, os);
      if (exe != null) return exe;
    }
    state("installing llama.cpp " + s.release + " " + accelerator);
    JsonObject release =
        JsonParser.parseString(
                get("https://api.github.com/repos/ggml-org/llama.cpp/releases/tags/" + s.release))
            .getAsJsonObject();
    String asset = "llama-" + s.release + "-bin-" + suffix;
    installAsset(release, asset, dir);
    if (os.equals("win") && accelerator.equals("cuda"))
      installAsset(release, "cudart-llama-bin-win-cuda-13.3-" + arch + ".zip", dir);
    Path exe = findExe(dir, os);
    if (exe == null) throw new IOException("Runtime archive does not contain llama-server");
    if (!os.equals("win")) exe.toFile().setExecutable(true, true);
    Files.writeString(dir.resolve("installed.ok"), s.release);
    return exe;
  }

  private void installAsset(JsonObject release, String name, Path dir) throws Exception {
    JsonObject asset = null;
    for (JsonElement e : release.getAsJsonArray("assets"))
      if (e.getAsJsonObject().get("name").getAsString().equals(name)) {
        asset = e.getAsJsonObject();
        break;
      }
    if (asset == null) throw new IOException("No runtime asset " + name);
    String digest =
        asset.has("digest") && !asset.get("digest").isJsonNull()
            ? asset.get("digest").getAsString()
            : "";
    if (!digest.startsWith("sha256:")) throw new IOException("Runtime asset has no SHA-256 digest");
    Path archive = root.resolve("downloads").resolve(name);
    download(
        asset.get("browser_download_url").getAsString(),
        archive,
        digest.substring(7),
        asset.get("size").getAsLong());
    Files.createDirectories(dir);
    extract(archive, dir);
  }

  private void launch(Path executable, Path model, String accelerator) throws Exception {
    if (closed) throw new InterruptedException("stopping");
    // Probe immediately before each launch, including CPU fallback, not before an install/download.
    runtimePort = LocalPorts.find(s.port);
    if (runtimePort != s.port)
      state("port " + s.port + " occupied; using private local port " + runtimePort);
    endpoint = "http://127.0.0.1:" + runtimePort + "/v1";
    List<String> args =
        new ArrayList<>(
            List.of(
                executable.toAbsolutePath().toString(),
                "--model",
                model.toAbsolutePath().toString(),
                "--alias",
                s.modelId,
                "--host",
                "127.0.0.1",
                "--port",
                Integer.toString(runtimePort),
                "--api-key",
                key,
                "--ctx-size",
                Integer.toString(s.context),
                "--threads",
                Integer.toString(s.threads),
                "--parallel",
                "1",
                "--jinja",
                "--n-gpu-layers",
                accelerator.equals("cpu") ? "0" : "auto",
                "--reasoning-budget",
                Integer.toString(s.reasoningBudget),
                "--reasoning-format",
                "deepseek"));
    ProcessBuilder.Redirect output = RuntimeLogs.output(root, this::state);
    ProcessBuilder pb =
        new ProcessBuilder(args)
            .directory(executable.getParent().toFile())
            .redirectErrorStream(true)
            .redirectOutput(output);
    pb.environment()
        .put(
            "LD_LIBRARY_PATH",
            executable.getParent().toAbsolutePath()
                + File.pathSeparator
                + pb.environment().getOrDefault("LD_LIBRARY_PATH", ""));
    synchronized (this) {
      if (closed) throw new InterruptedException("stopping");
      try {
        process = pb.start();
      } catch (IOException e) {
        if (output == ProcessBuilder.Redirect.DISCARD) throw e;
        state(
            "launch with file output failed: "
                + e.getMessage()
                + "; retrying without file logging");
        process = pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
      }
      ownership.launched(process, executable);
    }
    state("loading model (" + accelerator + ")");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(s.startupTimeout);
    while (!closed && System.nanoTime() < deadline) {
      if (!process.isAlive())
        throw new IOException("llama-server exited " + process.exitValue() + "; log: " + output);
      if (health()) return;
      Thread.sleep(500);
    }
    throw new IOException("Model startup timed out; log: " + output);
  }

  private boolean health() {
    try {
      String url = endpoint.replaceAll("/v1$", "") + "/health";
      boolean healthy =
          http.send(
                      HttpRequest.newBuilder(URI.create(url))
                          .timeout(Duration.ofSeconds(3))
                          .header("Authorization", "Bearer " + key)
                          .GET()
                          .build(),
                      HttpResponse.BodyHandlers.discarding())
                  .statusCode()
              == 200;
      if (!healthy || !s.backend.equals("managed")) return healthy;
      // /health may be public; require our fresh private API key at an authenticated route too.
      return http.send(
                  HttpRequest.newBuilder(URI.create(endpoint + "/models"))
                      .timeout(Duration.ofSeconds(3))
                      .header("Authorization", "Bearer " + key)
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.discarding())
              .statusCode()
          == 200;
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public boolean ready() {
    return ready && !closed;
  }

  @Override
  public String status() {
    return status;
  }

  @Override
  public String complete(String system, String user) throws Exception {
    return complete(system, user, ReasoningMode.NORMAL);
  }

  @Override
  public String complete(String system, String user, ReasoningMode mode) throws Exception {
    return complete(system, user, mode, null);
  }

  @Override
  public String complete(String system, String user, ReasoningMode mode, String schema)
      throws Exception {
    return complete(system, user, mode, schema, Long.MAX_VALUE);
  }

  @Override
  public String complete(
      String system, String user, ReasoningMode mode, String schema, long deadline)
      throws Exception {
    if (!ready()) throw new IOException("Local model unavailable");
    boolean thinking = mode != ReasoningMode.FINAL && (s.thinking || mode != ReasoningMode.NORMAL);
    long remaining =
        Math.min(
            1000L * (thinking ? s.thinkingTimeout : s.timeout),
            deadline - System.currentTimeMillis());
    if (remaining <= 0)
      throw new java.util.concurrent.TimeoutException("Decision observation expired");
    JsonObject body = requestBody(s, system, user, mode, schema);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint + "/chat/completions"))
            .timeout(Duration.ofMillis(remaining))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + key)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) throw new IOException("Model HTTP " + response.statusCode());
    return finalText(response.body());
  }

  public static JsonObject requestBody(Settings s, String system, String user, ReasoningMode mode) {
    return requestBody(s, system, user, mode, null);
  }

  public static JsonObject requestBody(
      Settings s, String system, String user, ReasoningMode mode, String customSchema) {
    boolean thinking = mode != ReasoningMode.FINAL && (s.thinking || mode != ReasoningMode.NORMAL);
    JsonObject body = new JsonObject();
    body.addProperty("model", s.modelId);
    body.addProperty("temperature", s.temperature);
    body.addProperty(
        "max_tokens",
        mode == ReasoningMode.FINAL
            ? (customSchema == null ? Math.max(256, s.tokens) : s.thinkingTokens)
            : thinking
                ? (mode == ReasoningMode.NORMAL
                    ? Math.min(512, s.thinkingTokens)
                    : s.thinkingTokens)
                : s.tokens);
    body.addProperty("stream", false);
    JsonArray messages = new JsonArray();
    for (String[] m : new String[][] {{"system", system}, {"user", user}}) {
      JsonObject j = new JsonObject();
      j.addProperty("role", m[0]);
      j.addProperty("content", m[1]);
      messages.add(j);
    }
    body.add("messages", messages);
    JsonObject schema =
        JsonParser.parseString(
                customSchema != null
                    ? customSchema
                    : """
                    {"type":"object","properties":{"action":{"type":"string","enum":["work","gather","deposit","rest","replan"]},"job_id":{"type":"string"},"material":{"type":"string"},"reason":{"type":"string"}},"required":["action","job_id","material","reason"],"additionalProperties":false}
                    """)
            .getAsJsonObject();
    JsonObject format = new JsonObject();
    format.addProperty("type", "json_schema");
    JsonObject jsonSchema = new JsonObject();
    jsonSchema.addProperty("name", "villager_action");
    jsonSchema.addProperty("strict", true);
    jsonSchema.add("schema", schema);
    format.add("json_schema", jsonSchema);
    body.add("response_format", format);
    if (s.thinkingOption) {
      JsonObject template = new JsonObject();
      template.addProperty("enable_thinking", thinking);
      body.add("chat_template_kwargs", template);
    }
    return body;
  }

  public static String finalText(String json) throws IOException {
    try {
      JsonObject choice =
          JsonParser.parseString(json)
              .getAsJsonObject()
              .getAsJsonArray("choices")
              .get(0)
              .getAsJsonObject();
      if (choice.has("finish_reason") && "length".equals(choice.get("finish_reason").getAsString()))
        throw new InvalidModelOutputException(
            "Model output limit reached; increase thinking-output-tokens or shorten the decision"
                + " context");
      JsonElement content = choice.getAsJsonObject("message").get("content");
      if (content == null
          || content.isJsonNull()
          || !content.isJsonPrimitive()
          || content.getAsString().isBlank())
        throw new InvalidModelOutputException(
            "Model returned no final decision; reasoning-only output is not an action");
      return content.getAsString();
    } catch (RuntimeException e) {
      throw new InvalidModelOutputException(
          "Model response has no valid final message: " + e.getClass().getSimpleName());
    }
  }

  private String get(String url) throws Exception {
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Civilizations/2.0")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200)
      throw new IOException("Metadata HTTP " + response.statusCode());
    return response.body();
  }

  private void download(String url, Path dest, String hash, long expected) throws Exception {
    if (hash == null || !hash.matches("[0-9a-fA-F]{64}"))
      throw new IOException("A SHA-256 checksum is required");
    if (!URI.create(url).getScheme().equals("https"))
      throw new IOException("Downloads must use HTTPS");
    Files.createDirectories(dest.getParent());
    if (Files.exists(dest)
        && (!(expected > 0) || Files.size(dest) == expected)
        && sha256(dest).equalsIgnoreCase(hash)) return;
    if (expected > 0
        && Files.getFileStore(dest.getParent()).getUsableSpace() < expected + 64L * 1024 * 1024)
      throw new IOException("Insufficient disk space to download " + dest.getFileName());
    Path part = Files.createTempFile(dest.getParent(), dest.getFileName() + ".", ".part");
    try {
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create(url))
                  .timeout(Duration.ofMinutes(90))
                  .header("User-Agent", "Civilizations/2.0")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream in = response.body()) {
        if (response.statusCode() != 200)
          throw new IOException("Download HTTP " + response.statusCode());
        long bytes = 0, nextLog = 256L * 1024 * 1024;
        byte[] buffer = new byte[1024 * 1024];
        try (OutputStream out = Files.newOutputStream(part)) {
          for (int n; (n = in.read(buffer)) != -1; ) {
            if (closed || Thread.currentThread().isInterrupted())
              throw new InterruptedException("Download stopped");
            bytes += n;
            if (bytes > Math.max(expected, 8L * 1024 * 1024 * 1024))
              throw new IOException("Download exceeds limit");
            out.write(buffer, 0, n);
            if (bytes >= nextLog) {
              state("downloaded " + (bytes / 1024 / 1024) + " MiB of " + dest.getFileName());
              nextLog += 256L * 1024 * 1024;
            }
          }
        }
        if (expected > 0 && bytes != expected)
          throw new IOException("Incomplete download: " + bytes + " / " + expected);
        if (!sha256(part).equalsIgnoreCase(hash))
          throw new IOException("Download checksum mismatch");
        try {
          Files.move(
              part, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    } finally {
      Files.deleteIfExists(part);
    }
  }

  public static String sha256(Path p) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    try (InputStream in = Files.newInputStream(p)) {
      byte[] b = new byte[1024 * 1024];
      for (int n; (n = in.read(b)) != -1; ) md.update(b, 0, n);
    }
    return HexFormat.of().formatHex(md.digest());
  }

  public static Path safeEntry(Path dir, String name) throws IOException {
    Path root = dir.toAbsolutePath().normalize(),
        p = root.resolve(name.replace('\\', '/')).normalize();
    if (!p.startsWith(root) || p.equals(root)) throw new IOException("Unsafe archive entry");
    return p;
  }

  private static void extract(Path archive, Path dir) throws IOException {
    if (archive.toString().endsWith(".zip"))
      try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
        for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
          Path p = safeEntry(dir, e.getName());
          if (e.isDirectory()) {
            Files.createDirectories(p);
            continue;
          }
          Files.createDirectories(p.getParent());
          try (OutputStream out = Files.newOutputStream(p)) {
            copyBounded(zip, out);
          }
          zip.closeEntry();
        }
      }
    else
      try (TarArchiveInputStream tar =
          new TarArchiveInputStream(new GzipCompressorInputStream(Files.newInputStream(archive)))) {
        List<String[]> links = new ArrayList<>();
        for (TarArchiveEntry e; (e = tar.getNextEntry()) != null; ) {
          if (e.getName().equals(".") || e.getName().equals("./")) continue;
          Path p = safeEntry(dir, e.getName());
          if (e.isDirectory()) {
            Files.createDirectories(p);
            continue;
          }
          if (e.isSymbolicLink()) {
            links.add(new String[] {e.getName(), e.getLinkName()});
            continue;
          }
          if (!e.isFile()) throw new IOException("Unsupported archive entry");
          Files.createDirectories(p.getParent());
          try (OutputStream out = Files.newOutputStream(p)) {
            copyBounded(tar, out);
          }
          if ((e.getMode() & 0111) != 0) p.toFile().setExecutable(true, true);
        }
        // Materialize internal library symlinks as regular files, never external links.
        for (String[] link : links) {
          Path p = safeEntry(dir, link[0]);
          Path target = p.getParent().resolve(link[1]).normalize();
          if (!target.startsWith(dir.toAbsolutePath().normalize()) || !Files.isRegularFile(target))
            throw new IOException("Unsafe library link");
          Files.copy(target, p, StandardCopyOption.REPLACE_EXISTING);
        }
      }
  }

  private static void copyBounded(InputStream in, OutputStream out) throws IOException {
    byte[] b = new byte[65536];
    long size = 0;
    for (int n; (n = in.read(b)) != -1; ) {
      size += n;
      if (size > 2L * 1024 * 1024 * 1024) throw new IOException("Archive entry too large");
      out.write(b, 0, n);
    }
  }

  private static Path findExe(Path dir, String os) throws IOException {
    try (var files = Files.walk(dir)) {
      return files
          .filter(
              p ->
                  p.getFileName()
                      .toString()
                      .equals(os.equals("win") ? "llama-server.exe" : "llama-server"))
          .findFirst()
          .orElse(null);
    }
  }

  private static String safeFile(String s) throws IOException {
    if (s == null || !s.matches("[A-Za-z0-9_.-]+\\.gguf"))
      throw new IOException("Invalid model filename");
    return s;
  }

  private static String os() {
    String name = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    return name.contains("win") ? "win" : name.contains("mac") ? "macos" : "ubuntu";
  }

  private static String arch() {
    String a = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
    if (Set.of("amd64", "x86_64").contains(a)) return "x64";
    if (Set.of("aarch64", "arm64").contains(a)) return "arm64";
    throw new IllegalArgumentException("Unsupported CPU architecture: " + a);
  }

  private synchronized void kill() {
    Process p = process;
    process = null;
    if (p != null && p.isAlive()) {
      p.destroy();
      if (p.isAlive()) p.destroyForcibly();
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    ready = false;
    status = "stopped";
    lifecycle.shutdownNow();
    kill();
    if (ownership != null) {
      try {
        ownership.close();
      } catch (IOException e) {
        log.accept("Could not release runtime lease: " + e.getMessage());
      }
      ownership = null;
    }
    if (Thread.currentThread() != shutdownHook && started && s.enabled) {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException ignored) {
        // JVM shutdown already began; the hook only closes this instance's owned process.
      }
    }
  }
}
