package com.streamarr.server.services.streaming.ffmpeg;

import com.streamarr.server.exceptions.TranscodeException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LocalFfmpegProcessManager implements FfmpegProcessManager {

  private static final long GRACEFUL_SHUTDOWN_SECONDS = 5;

  private record ProcessKey(UUID sessionId, String variantLabel) {}

  private record ManagedProcess(Process process, StderrDrainer drainer) {}

  private final ConcurrentHashMap<ProcessKey, ManagedProcess> processes = new ConcurrentHashMap<>();

  @Override
  public Process startProcess(
      UUID sessionId, String variantLabel, List<String> command, Path workingDir) {
    var key = new ProcessKey(sessionId, variantLabel);
    if (processes.containsKey(key)) {
      throw duplicateProcess(sessionId, variantLabel);
    }

    try {
      var processBuilder = new ProcessBuilder(command);
      processBuilder.directory(workingDir.toFile());

      var process = processBuilder.start();
      var drainer = new StderrDrainer(process.getErrorStream());
      var managed = new ManagedProcess(process, drainer);
      var existing = processes.putIfAbsent(key, managed);
      if (existing != null) {
        shutdownManagedProcess(managed, sessionId);
        throw duplicateProcess(sessionId, variantLabel);
      }

      log.info(
          "Started FFmpeg process (PID {}) for session {} variant {}",
          process.pid(),
          sessionId,
          variantLabel);
      return process;
    } catch (IOException e) {
      log.error("Failed to start FFmpeg process for session: {}", sessionId, e);
      throw new TranscodeException(TranscodeException.GENERIC_MESSAGE, e);
    }
  }

  private TranscodeException duplicateProcess(UUID sessionId, String variantLabel) {
    log.error(
        "Refusing duplicate FFmpeg process for session {} variant {}", sessionId, variantLabel);
    return new TranscodeException(TranscodeException.GENERIC_MESSAGE);
  }

  @Override
  public void stopProcess(UUID sessionId) {
    var entriesToStop =
        processes.entrySet().stream()
            .filter(entry -> entry.getKey().sessionId().equals(sessionId))
            .toList();

    for (var entry : entriesToStop) {
      shutdownManagedProcess(entry.getValue(), sessionId);
      processes.remove(entry.getKey(), entry.getValue());
    }

    if (!entriesToStop.isEmpty()) {
      log.info("Stopped FFmpeg process(es) for session {}", sessionId);
    }
  }

  private void shutdownManagedProcess(ManagedProcess managed, UUID sessionId) {
    if (managed == null) {
      return;
    }

    if (!managed.process().isAlive()) {
      managed.drainer().close();
      return;
    }

    try {
      sendQuitSignal(managed.process(), sessionId);
      awaitShutdown(managed.process(), sessionId);
    } finally {
      if (!managed.process().isAlive()) {
        managed.drainer().close();
      }
    }
  }

  private void sendQuitSignal(Process process, UUID sessionId) {
    try {
      var outputStream = process.getOutputStream();
      outputStream.write('q');
      outputStream.flush();
    } catch (IOException e) {
      log.debug("Failed to write quit signal to FFmpeg stdin for session {}", sessionId);
    }
  }

  private void awaitShutdown(Process process, UUID sessionId) {
    try {
      if (process.waitFor(GRACEFUL_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
        return;
      }

      log.warn("FFmpeg did not stop gracefully for session {}, forcing", sessionId);
      process.destroyForcibly();
      if (!process.waitFor(GRACEFUL_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
        throw new TranscodeException(TranscodeException.GENERIC_MESSAGE);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new TranscodeException(TranscodeException.GENERIC_MESSAGE, e);
    }
  }

  @Override
  public boolean isRunning(UUID sessionId) {
    return processes.entrySet().stream()
        .filter(e -> e.getKey().sessionId().equals(sessionId))
        .anyMatch(e -> isAliveOrCleanup(e.getKey(), e.getValue()));
  }

  @Override
  public boolean isRunning(UUID sessionId, String variantLabel) {
    var key = new ProcessKey(sessionId, variantLabel);
    var managed = processes.get(key);
    if (managed == null) {
      return false;
    }

    return isAliveOrCleanup(key, managed);
  }

  private boolean isAliveOrCleanup(ProcessKey key, ManagedProcess managed) {
    if (!managed.process().isAlive()) {
      logExitDetails(key, managed);
      managed.drainer().close();
      processes.remove(key, managed);
      return false;
    }

    return true;
  }

  private void logExitDetails(ProcessKey key, ManagedProcess managed) {
    try {
      var exitCode = managed.process().exitValue();
      var recentLines = managed.drainer().getRecentOutput();
      if (!recentLines.isEmpty()) {
        var stderr = String.join("\n", recentLines);
        log.warn(
            "FFmpeg exited with code {} for session {} variant {}: {}",
            exitCode,
            key.sessionId(),
            key.variantLabel(),
            stderr.substring(0, Math.min(stderr.length(), 2000)));
      }
    } catch (Exception _) {
      log.debug("Could not read FFmpeg exit details for session {}", key.sessionId());
    }
  }
}
