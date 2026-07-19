package com.streamarr.server.services.streaming.ffmpeg;

import com.streamarr.server.domain.streaming.ProducerEnd;
import com.streamarr.server.exceptions.TranscodeException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

  private record ManagedProcess(Process process, StderrDrainer drainer, UUID attemptId) {}

  private final ConcurrentHashMap<ProcessKey, ManagedProcess> processes = new ConcurrentHashMap<>();

  // Exit evidence survives the death transition until recovery consumes it (or the next attempt's
  // death overwrites it). Planned stops never write here.
  private final ConcurrentHashMap<ProcessKey, ProducerEnd> retainedExits =
      new ConcurrentHashMap<>();

  @Override
  public Process startProcess(
      UUID sessionId, String variantLabel, List<String> command, Path workingDir) {
    return startProcess(sessionId, variantLabel, null, command, workingDir);
  }

  @Override
  public Process startProcess(
      UUID sessionId, String variantLabel, UUID attemptId, List<String> command, Path workingDir) {
    try {
      var processBuilder = new ProcessBuilder(command);
      processBuilder.directory(workingDir.toFile());

      var process = processBuilder.start();
      var drainer = new StderrDrainer(process.getErrorStream());
      processes.put(
          new ProcessKey(sessionId, variantLabel), new ManagedProcess(process, drainer, attemptId));

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

    // A planned session stop is the end of the line for its evidence: nothing consumes it after
    // teardown, and unconsumed entries would otherwise outlive the session for the JVM lifetime.
    retainedExits.keySet().removeIf(key -> key.sessionId().equals(sessionId));

    if (!keysToRemove.isEmpty()) {
      log.info("Stopped FFmpeg process(es) for session {}", sessionId);
    }
  }

  @Override
  public void stopProcess(UUID sessionId, String variantLabel) {
    var key = new ProcessKey(sessionId, variantLabel);
    var managed = processes.remove(key);
    shutdownManagedProcess(managed, sessionId);
    retainedExits.remove(key);
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
          exitDetail(exitCode, managed.drainer().getRecentOutput()));
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

  @Override
  public Optional<ProducerEnd> consumeExit(
      UUID sessionId, String variantLabel, UUID expectedAttemptId) {
    var key = new ProcessKey(sessionId, variantLabel);
    var retained = retainedExits.get(key);
    if (retained == null || !expectedAttemptId.equals(retained.attemptId())) {
      return Optional.empty();
    }

    // Consume-once must be atomic: only the caller whose remove wins receives the evidence.
    if (!retainedExits.remove(key, retained)) {
      return Optional.empty();
    }
    return Optional.of(retained);
  }

  private boolean isAliveOrCleanup(ProcessKey key, ManagedProcess managed) {
    if (!managed.process().isAlive()) {
      retainExitEvidence(key, managed);
      managed.drainer().close();
      processes.remove(key);
      return false;
    }

    return true;
  }

  private void retainExitEvidence(ProcessKey key, ManagedProcess managed) {
    try {
      var exitCode = managed.process().exitValue();
      var detail = exitDetail(exitCode, managed.drainer().getRecentOutput());
      log.warn(
          "FFmpeg exited with code {} for session {} variant {}: {}",
          exitCode,
          key.sessionId(),
          key.variantLabel(),
          detail);
      if (managed.attemptId() == null) {
        return;
      }

      retainedExits.put(
          key,
          ProducerEnd.builder()
              .attemptId(managed.attemptId())
              .kind(ProducerEnd.EndKind.PROCESS_EXIT)
              .detail(detail)
              .at(Instant.now())
              .build());
    } catch (Exception e) {
      // This guard sits on the sole producer of local death evidence; surface why it failed.
      log.warn("Could not retain FFmpeg exit evidence for session {}", key.sessionId(), e);
    }
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
