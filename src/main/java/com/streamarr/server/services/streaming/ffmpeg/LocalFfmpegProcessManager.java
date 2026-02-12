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

  private final ConcurrentHashMap<ProcessKey, Process> processes = new ConcurrentHashMap<>();

  @Override
  public Process startProcess(
      UUID sessionId, String variantLabel, List<String> command, Path workingDir) {
    try {
      var processBuilder = new ProcessBuilder(command);
      processBuilder.directory(workingDir.toFile());
      processBuilder.redirectErrorStream(false);

      var process = processBuilder.start();
      processes.put(new ProcessKey(sessionId, variantLabel), process);

      log.info(
          "Started FFmpeg process (PID {}) for session {} variant {}",
          process.pid(),
          sessionId,
          variantLabel);
      return process;
    } catch (IOException e) {
      throw new TranscodeException("Failed to start FFmpeg process for session: " + sessionId, e);
    }
  }

  @Override
  public void stopProcess(UUID sessionId) {
    var keysToRemove =
        processes.keySet().stream().filter(key -> key.sessionId().equals(sessionId)).toList();

    for (var key : keysToRemove) {
      var process = processes.remove(key);
      if (process == null || !process.isAlive()) {
        continue;
      }

      sendQuitSignal(process, sessionId);
      awaitGracefulShutdown(process, sessionId);
    }

    if (!keysToRemove.isEmpty()) {
      log.info("Stopped FFmpeg process(es) for session {}", sessionId);
    }
  }

  private void sendQuitSignal(Process process, UUID sessionId) {
    try {
      var outputStream = process.getOutputStream();
      outputStream.write('q');
      outputStream.flush();
    } catch (IOException _) {
      log.debug("Failed to write quit signal to FFmpeg stdin for session {}", sessionId);
    }
  }

  private void awaitGracefulShutdown(Process process, UUID sessionId) {
    try {
      if (!process.waitFor(GRACEFUL_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
        log.warn("FFmpeg did not stop gracefully for session {}, forcing", sessionId);
        process.destroyForcibly();
      }
    } catch (InterruptedException _) {
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
    var process = processes.get(key);
    if (process == null) {
      return false;
    }

    return isAliveOrCleanup(key, process);
  }

  private boolean isAliveOrCleanup(ProcessKey key, Process process) {
    if (!process.isAlive()) {
      processes.remove(key);
      return false;
    }

    return true;
  }
}
