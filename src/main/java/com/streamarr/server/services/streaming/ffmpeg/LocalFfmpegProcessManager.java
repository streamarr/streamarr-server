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

  private final ConcurrentHashMap<UUID, Process> processes = new ConcurrentHashMap<>();

  @Override
  public Process startProcess(UUID sessionId, List<String> command, Path workingDir) {
    try {
      var processBuilder = new ProcessBuilder(command);
      processBuilder.directory(workingDir.toFile());
      processBuilder.redirectErrorStream(false);

      var process = processBuilder.start();
      processes.put(sessionId, process);

      log.info("Started FFmpeg process (PID {}) for session {}", process.pid(), sessionId);
      return process;
    } catch (IOException e) {
      throw new TranscodeException("Failed to start FFmpeg process for session: " + sessionId, e);
    }
  }

  @Override
  public void stopProcess(UUID sessionId) {
    var process = processes.remove(sessionId);
    if (process == null || !process.isAlive()) {
      return;
    }

    sendQuitSignal(process, sessionId);
    awaitGracefulShutdown(process, sessionId);
    log.info("Stopped FFmpeg process for session {}", sessionId);
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
    var process = processes.get(sessionId);
    if (process == null) {
      return false;
    }
    if (!process.isAlive()) {
      processes.remove(sessionId);
      return false;
    }
    return true;
  }
}
