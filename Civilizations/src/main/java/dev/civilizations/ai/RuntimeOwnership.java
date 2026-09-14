package dev.civilizations.ai;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Per-directory lease and exact process identity, without storing commands or API credentials. */
final class RuntimeOwnership implements AutoCloseable {
  private record Owner(
      long owner, String ownerStarted, long child, String childStarted, String executable) {}

  private final Path root, record;
  private final FileChannel channel;
  private final FileLock lock;

  RuntimeOwnership(Path root, Consumer<String> log) throws Exception {
    this.root = root.toAbsolutePath().normalize();
    Files.createDirectories(this.root);
    record = this.root.resolve("process-owner.json");
    channel =
        FileChannel.open(
            this.root.resolve("runtime.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    FileLock acquired;
    try {
      acquired = channel.tryLock();
    } catch (OverlappingFileLockException e) {
      channel.close();
      throw new IOException("Managed runtime already owned by an active server", e);
    }
    if (acquired == null) {
      channel.close();
      throw new IOException("Managed runtime already owned by an active server");
    }
    lock = acquired;
    try {
      reclaim(log);
    } catch (Exception e) {
      close();
      throw e;
    }
  }

  private void reclaim(Consumer<String> log) throws Exception {
    if (!Files.exists(record)) return;
    Owner saved = new Gson().fromJson(Files.readString(record), Owner.class);
    if (saved == null) return;
    var child = ProcessHandle.of(saved.child());
    if (child.isEmpty() || !identity(child.get()).equals(saved.childStarted())) return;
    var owner = ProcessHandle.of(saved.owner());
    if (owner.isPresent() && identity(owner.get()).equals(saved.ownerStarted()))
      throw new IOException("Recorded runtime owner is still alive");
    Path executable = Path.of(saved.executable()).toAbsolutePath().normalize();
    String liveCommand = child.get().info().command().orElse("");
    if (!executable.startsWith(root.resolve("runtime"))
        || liveCommand.isEmpty()
        || !Path.of(liveCommand).toAbsolutePath().normalize().equals(executable))
      throw new IOException("Recorded child identity could not be verified; no process terminated");
    child.get().destroyForcibly();
    child.get().onExit().get(10, TimeUnit.SECONDS);
    log.accept("Cleaned up owned inference process left by an exited server");
    Files.deleteIfExists(record);
  }

  void launched(Process process, Path executable) throws IOException {
    ProcessHandle self = ProcessHandle.current();
    Owner owner =
        new Owner(
            self.pid(),
            identity(self),
            process.pid(),
            identity(process.toHandle()),
            executable.toAbsolutePath().normalize().toString());
    if (owner.ownerStarted().isEmpty() || owner.childStarted().isEmpty())
      throw new IOException("Cannot record runtime process identity");
    Path part = root.resolve("process-owner.tmp");
    Files.writeString(part, new Gson().toJson(owner));
    Files.move(part, record, StandardCopyOption.REPLACE_EXISTING);
  }

  private static String identity(ProcessHandle process) {
    return process.info().startInstant().map(Object::toString).orElse("");
  }

  public void close() throws IOException {
    // Keep the identity record: if kill failed, the next owner can still reclaim the child.
    try {
      if (lock.isValid()) lock.release();
    } finally {
      channel.close();
    }
  }
}
