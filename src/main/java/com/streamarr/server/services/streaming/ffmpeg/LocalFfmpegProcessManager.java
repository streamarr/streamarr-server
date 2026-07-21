package com.streamarr.server.services.streaming.ffmpeg;

import com.streamarr.server.exceptions.TranscodeException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
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
  private static final int STDERR_TAIL_LIMIT = 2000;

  private record ProcessKey(UUID sessionId, String variantLabel) {}

  private record ManagedProcess(Process process, StderrDrainer drainer) {}

  private final ConcurrentHashMap<ProcessKey, ManagedProcess> processes = new ConcurrentHashMap<>();

  @Override
  public Process startProcess(
      UUID sessionId, String variantLabel, List<String> command, Path workingDir) {
    try {
      var processBuilder = new ProcessBuilder(command);
      processBuilder.directory(workingDir.toFile());

      var process = processBuilder.start();
      var drainer = new StderrDrainer(process.getErrorStream());
      processes.put(new ProcessKey(sessionId, variantLabel), new ManagedProcess(process, drainer));

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

  @Override
  public void stopProcess(UUID sessionId) {
    var keysToRemove =
        processes.keySet().stream().filter(key -> key.sessionId().equals(sessionId)).toList();

    for (var key : keysToRemove) {
      shutdownManagedProcess(processes.remove(key), sessionId);
    }

    if (!keysToRemove.isEmpty()) {
      log.info("Stopped FFmpeg process(es) for session {}", sessionId);
    }
  }

  @Override
  public void stopProcess(UUID sessionId, String variantLabel) {
    var key = new ProcessKey(sessionId, variantLabel);
    var managed = processes.remove(key);
    shutdownManagedProcess(managed, sessionId);
    if (managed != null) {
      log.info("Stopped FFmpeg process for session {} variant {}", sessionId, variantLabel);
    }
  }

  private void shutdownManagedProcess(ManagedProcess managed, UUID sessionId) {
    if (managed == null) {
      return;
    }

    if (!managed.process().isAlive()) {
      logUnobservedExit(managed, sessionId);
      managed.drainer().close();
      return;
    }

    sendQuitSignal(managed.process(), sessionId);
    awaitGracefulShutdown(managed.process(), sessionId);
    managed.drainer().close();
  }

  /** A corpse disposed of on a planned stop still gets its crash detail into the log. */
  private void logUnobservedExit(ManagedProcess managed, UUID sessionId) {
    try {
      var exitCode = managed.process().exitValue();
      if (exitCode == 0) {
        return;
      }
      log.warn(
          "FFmpeg had already exited with code {} for session {} before its planned stop: {}",
          exitCode,
          sessionId,
          exitDetail(exitCode, recentStderr(managed)));
    } catch (Exception e) {
      log.debug("Could not read FFmpeg exit details for session {}", sessionId, e);
    }
  }

  private void sendQuitSignal(Process process, UUID sessionId) {
    try {
      var outputStream = process.getOutputStream();
      outputStream.write('q');
      outputStream.flush();
    } catch (IOException e) {
      log.debug("Failed to write quit signal to FFmpeg stdin for session {}", sessionId, e);
    }
  }

  private void awaitGracefulShutdown(Process process, UUID sessionId) {
    try {
      if (!process.waitFor(GRACEFUL_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
        log.warn("FFmpeg did not stop gracefully for session {}, forcing", sessionId);
        process.destroyForcibly();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
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
      logObservedExit(key, managed);
      managed.drainer().close();
      processes.remove(key);
      return false;
    }

    return true;
  }

  /** The death site logs its own crash detail; nothing downstream needs to reconstruct it. */
  private void logObservedExit(ProcessKey key, ManagedProcess managed) {
    try {
      var exitCode = managed.process().exitValue();
      if (exitCode == 0) {
        log.info("FFmpeg completed for session {} variant {}", key.sessionId(), key.variantLabel());
        return;
      }
      log.warn(
          "FFmpeg exited with code {} for session {} variant {}: {}",
          exitCode,
          key.sessionId(),
          key.variantLabel(),
          exitDetail(exitCode, recentStderr(managed)));
    } catch (Exception e) {
      log.warn("Could not read FFmpeg exit details for session {}", key.sessionId(), e);
    }
  }

  /** The process is dead, so end-of-stream is imminent; waiting avoids a truncated stderr tail. */
  private static List<String> recentStderr(ManagedProcess managed) {
    return managed.drainer().awaitRecentOutput(Duration.ofSeconds(1));
  }

  private static String exitDetail(int exitCode, List<String> recentLines) {
    var detail = "exit code " + exitCode;
    if (recentLines.isEmpty()) {
      return detail;
    }

    var stderr = String.join("\n", recentLines);
    return detail + ": " + stderr.substring(0, Math.min(stderr.length(), STDERR_TAIL_LIMIT));
  }
}
