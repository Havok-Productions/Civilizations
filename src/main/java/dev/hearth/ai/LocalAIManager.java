package dev.hearth.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hearth.HearthPlugin;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Bootstraps a self-contained local LLM runtime ("Quen") inside this plugin's
 * own folder and keeps its {@code llama-server} process alive for the Quen
 * agents.
 *
 * <p>Lifecycle — all on one dedicated daemon thread, never a Bukkit thread:
 * <ol>
 *   <li>Resolve the latest llama.cpp release (GitHub API) and download the
 *       platform binary into {@code local-ai/bin/} (skipped if already present).</li>
 *   <li>Download the Qwen GGUF model into {@code local-ai/models/}
 *       (skipped if already present — this is the ~1 GB step, first run only).</li>
 *   <li>Launch {@code llama-server} bound to 127.0.0.1 (port scan if the
 *       preferred port is busy), then poll {@code /v1/models} until it answers.</li>
 *   <li>Watch the process; if it dies, the state goes to FAILED so the agents
 *       fall back to local rules and the admin sees a clear status.</li>
 * </ol>
 *
 * <p><b>Folia-safety:</b> only plain Java (NIO, JDK HttpClient, Process) and
 * {@code plugin.getLogger()} are used here — no world/entity access, so it is
 * safe to run from any thread.
 *
 * <p>Supported platforms (verified assets): Windows x64 (zip), Ubuntu x64
 * (tar.gz), macOS x64/arm64 (tar.gz). Everything else fails with a clear message.
 */
public class LocalAIManager {

    public enum State {
        STOPPED,
        DOWNLOADING_RUNTIME,
        DOWNLOADING_MODEL,
        STARTING,
        READY,
        FAILED
    }

    private static final String GITHUB_LATEST_RELEASES =
            "https://api.github.com/repos/ggml-org/llama.cpp/releases?per_page=1";

    private final HearthPlugin plugin;
    private final File baseDir;   // plugins/Civilizations/local-ai
    private final File binDir;
    private final File modelsDir;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private volatile State state = State.STOPPED;
    private volatile String detail = "not started";
    private volatile int port;
    private volatile Process serverProcess;
    private Thread bootstrapThread;

    public LocalAIManager(HearthPlugin plugin) {
        this.plugin = plugin;
        this.baseDir = new File(plugin.getDataFolder(), "local-ai");
        this.binDir = new File(baseDir, "bin");
        this.modelsDir = new File(baseDir, "models");
    }

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    /**
     * Kick off the bootstrap if not already running/ready. Non-blocking.
     *
     * @return true if a bootstrap is (now) running or already finished
     */
    public synchronized boolean start() {
        if (state == State.READY) {
            return true;
        }
        if (bootstrapThread != null && bootstrapThread.isAlive()) {
            return false; // already working
        }
        bootstrapThread = new Thread(this::bootstrap, "quen-local-ai");
        bootstrapThread.setDaemon(true);
        bootstrapThread.start();
        return true;
    }

    public boolean ready() {
        return state == State.READY;
    }

    /**
     * OpenAI-compatible base URL for the local server (valid while READY).
     */
    public String baseUrl() {
        return "http://127.0.0.1:" + port + "/v1";
    }

    public State state() {
        return state;
    }

    /**
     * One-line status for /hearth ai status.
     */
    public String status() {
        StringBuilder sb = new StringBuilder(state.name().toLowerCase(Locale.ROOT));
        if (!detail.isEmpty()) {
            sb.append(" (").append(detail).append(")");
        }
        if (state == State.READY) {
            sb.append(" at ").append(baseUrl());
        }
        return sb.toString();
    }

    /**
     * Stop the local server and any in-flight bootstrap. Safe to call more
     * than once (e.g. on disable and on reload).
     */
    public synchronized void shutdown() {
        state = State.STOPPED; // wake the watchdog
        detail = "stopped";
        Thread t = bootstrapThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        killServer();
    }

    // ----------------------------------------------------------------
    // Bootstrap
    // ----------------------------------------------------------------

    private void bootstrap() {
        try {
            binDir.mkdirs();
            modelsDir.mkdirs();
            String os = osName();
            String arch = archName();
            String serverExe = "llama-server" + ("win".equals(os) ? ".exe" : "");
            File serverBin = new File(binDir, serverExe);

            // --- 1. llama.cpp runtime -----------------------------------
            if (serverBin.exists() && serverBin.length() > 0) {
                log("llama-server binary already present - skipping runtime download.");
            } else {
                setState(State.DOWNLOADING_RUNTIME, "resolving latest llama.cpp release");
                String tag = fetchLatestLlamaTag();
                String asset = assetName(os, arch, tag);
                String url = "https://github.com/ggml-org/llama.cpp/releases/download/" + tag + "/" + asset;
                log("Downloading runtime: " + url);
                File archive = new File(baseDir, "download-" + asset);
                try {
                    downloadToFile(url, archive, false);
                    if (asset.endsWith(".zip")) {
                        extractZip(archive, binDir);
                    } else {
                        extractTarGz(archive, binDir);
                    }
                } finally {
                    deleteQuietly(archive);
                }
                makeExecutable(serverBin);
            }

            // --- 2. Model -------------------------------------------------
            String modelFile = plugin.aiLocalModelFile();
            File model = new File(modelsDir, modelFile);
            if (model.exists() && model.length() > 0) {
                log("Model already present - skipping model download.");
            } else {
                setState(State.DOWNLOADING_MODEL, "downloading " + modelFile + " (first run, may be ~1 GB)");
                String repo = plugin.aiLocalModelRepo();
                String url = "https://huggingface.co/" + repo + "/resolve/main/" + modelFile;
                log("Downloading model: " + url);
                downloadToFile(url, model, true);
            }

            // --- 3. Start the server --------------------------------------
            setState(State.STARTING, "starting llama-server");
            int chosen = choosePort(plugin.aiLocalPort(), 10);
            if (chosen < 0) {
                throw new IllegalStateException("no free port in " + plugin.aiLocalPort()
                        + ".." + (plugin.aiLocalPort() + 9));
            }
            port = chosen;
            File logFile = new File(baseDir, "server.log");
            ProcessBuilder pb = new ProcessBuilder(
                    serverBin.getAbsolutePath(),
                    "-m", model.getAbsolutePath(),
                    "--host", "127.0.0.1",
                    "--port", String.valueOf(chosen),
                    "-c", String.valueOf(plugin.aiLocalContext()),
                    "-t", String.valueOf(plugin.aiLocalThreads()),
                    "--jinja",
                    "--parallel", "1")
                    .directory(baseDir)
                    .redirectOutput(logFile)
                    .redirectErrorStream(true);
            Process process = pb.start();
            serverProcess = process;
            log("llama-server started (pid " + process.pid() + ") - waiting for it to load the model (log: " + logFile + ")");

            if (!waitForReady(chosen, 240)) {
                killServer();
                throw new IllegalStateException("llama-server did not become ready in 240s - see local-ai/server.log");
            }
            setState(State.READY, "serving " + modelFile + " on 127.0.0.1:" + chosen);
            plugin.getLogger().info("Quen local AI is READY: " + baseUrl() + " (model: " + modelFile + ")");

            // --- 4. Watchdog ----------------------------------------------
            watchdogLoop();
        } catch (Throwable t) {
            killServer();
            setState(State.FAILED, t.getMessage() == null ? t.toString() : t.getMessage());
            plugin.getLogger().severe("Quen local AI bootstrap failed: " + t);
            t.printStackTrace();
        }
    }

    private void watchdogLoop() {
        while (state == State.READY) {
            Process p = serverProcess;
            if (p == null || !p.isAlive()) {
                setState(State.FAILED, "llama-server process exited unexpectedly - see local-ai/server.log");
                plugin.getLogger().severe("Quen local AI: llama-server exited unexpectedly; see local-ai/server.log");
                return;
            }
            try {
                Thread.sleep(15_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ----------------------------------------------------------------
    // Platform / download helpers (plain Java only)
    // ----------------------------------------------------------------

    private String fetchLatestLlamaTag() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_LATEST_RELEASES))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Hearth-Minecraft-Plugin")
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("GitHub API HTTP " + resp.statusCode());
        }
        JsonArray releases = JsonParser.parseString(resp.body()).getAsJsonArray();
        String tag = releases.get(0).getAsJsonObject().get("tag_name").getAsString();
        if (tag == null || tag.isBlank()) {
            throw new IllegalStateException("GitHub did not return a release tag");
        }
        log("Latest llama.cpp release: " + tag);
        return tag;
    }

    private static String assetName(String os, String arch, String tag) {
        if ("win".equals(os)) {
            // The x64 zip runs on Windows ARM via emulation.
            return "llama-" + tag + "-bin-win-cpu-x64.zip";
        }
        if ("mac".equals(os)) {
            return "llama-" + tag + "-bin-macos-" + arch + ".tar.gz";
        }
        if ("x64".equals(arch)) {
            return "llama-" + tag + "-bin-ubuntu-x64.tar.gz";
        }
        throw new IllegalStateException("No prebuilt llama.cpp asset for this platform (linux/" + arch + ")");
    }

    private static String osName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("win")) {
            return "win";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "mac";
        }
        return "linux";
    }

    private static String archName() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        return "x64";
    }

    private void downloadToFile(String url, File dest, boolean progress) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(90))
                .GET()
                .build();
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            resp.body().close();
            deleteQuietly(tmp);
            throw new IllegalStateException("download failed with HTTP " + resp.statusCode() + ": " + url);
        }
        long lastLog = System.currentTimeMillis();
        long total = 0;
        try (InputStream in = new BufferedInputStream(resp.body());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                if (progress && System.currentTimeMillis() - lastLog > 10_000L) {
                    lastLog = System.currentTimeMillis();
                    log("  downloaded " + (total / 1024 / 1024) + " MB...");
                }
            }
            out.flush();
        }
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        log("Downloaded " + (total / 1024 / 1024) + " MB -> " + dest.getName());
    }

    private static void extractZip(File zip, File outDir) throws IOException {
        outDir.mkdirs();
        File canonicalOut = outDir.getCanonicalFile();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(outDir, entry.getName()).getCanonicalFile();
                if (!out.toPath().startsWith(canonicalOut.toPath())) {
                    throw new IOException("zip-slip rejected entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    out.getParentFile().mkdirs();
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        zis.transferTo(os);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void extractTarGz(File archive, File outDir) throws Exception {
        outDir.mkdirs();
        ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", archive.getAbsolutePath(), "-C", outDir.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output;
        try (InputStream is = p.getInputStream()) {
            output = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException("tar extraction failed (exit " + exit + "): " + output.trim());
        }
    }

    private static void makeExecutable(File f) {
        try {
            Files.setPosixFilePermissions(f.toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (Throwable ignored) {
            // Windows (no POSIX permissions) or a read-only FS - not a problem there.
        }
    }

    private static int choosePort(int base, int attempts) {
        for (int i = 0; i < attempts; i++) {
            int p = base + i;
            try (ServerSocket s = new ServerSocket(p)) {
                s.setReuseAddress(false);
                return p; // free
            } catch (IOException busy) {
                // taken - try the next one
            }
        }
        return -1;
    }

    private boolean waitForReady(int port, int maxSeconds) {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Process p = serverProcess;
            if (p != null && !p.isAlive()) {
                return false; // died while loading
            }
            if (state == State.STOPPED) {
                return false; // we were shut down meanwhile
            }
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/v1/models"))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 == 2) {
                    return true;
                }
            } catch (Exception ignored) {
                // not up yet
            }
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void killServer() {
        Process p = serverProcess;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        serverProcess = null;
    }

    private void setState(State s, String d) {
        state = s;
        detail = d;
        if (s != State.READY && s != State.FAILED) {
            log(d);
        }
    }

    private void log(String msg) {
        plugin.getLogger().info("Quen local AI: " + msg);
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists() && !f.delete()) {
            f.deleteOnExit();
        }
    }
}
