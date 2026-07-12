package com.streamarr.transcode.engine.ffmpeg;

import com.streamarr.transcode.engine.error.TranscodeException;
import com.streamarr.transcode.engine.model.TranscodeJobRef;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LocalFfmpegProcessManager implements FfmpegProcessManager {

  private static final long GRACEFUL_SHUTDOWN_SECONDS = 5;

  private record ManagedProcess(
      Process process, StderrDrainer drainer, AtomicBoolean stopRequested) {

    private void requestStop() {
      if (process.isAlive()) {
        stopRequested.set(true);
      }
    }
  }

  private final ConcurrentHashMap<FfmpegProcessKey, ManagedProcess> processes =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<FfmpegProcessKey, FfmpegProcessObservation> terminalObservations =
      new ConcurrentHashMap<>();

  @Override
  public Process startProcess(FfmpegProcessKey key, List<String> command, Path workingDir) {
    if (processes.containsKey(key)) {
      throw duplicateProcess(key);
    }

    try {
      var processBuilder = new ProcessBuilder(command);
      processBuilder.directory(workingDir.toFile());

      var process = processBuilder.start();
      var drainer = new StderrDrainer(process.getErrorStream());
      var managed = new ManagedProcess(process, drainer, new AtomicBoolean());
      var existing = processes.putIfAbsent(key, managed);
      if (existing != null) {
        shutdownManagedProcess(managed, key.jobRef());
        throw duplicateProcess(key);
      }
      terminalObservations.remove(key);

      log.info(
          "Started FFmpeg process (PID {}) for job {} generation {} rendition {}",
          process.pid(),
          key.jobRef().jobId(),
          key.jobRef().generation(),
          key.renditionLabel());
      return process;
    } catch (IOException exception) {
      log.error("Failed to start FFmpeg process for job: {}", key.jobRef().jobId(), exception);
      throw new TranscodeException(TranscodeException.GENERIC_MESSAGE, exception);
    }
  }

  private TranscodeException duplicateProcess(FfmpegProcessKey key) {
    log.error(
        "Refusing duplicate FFmpeg process for job {} generation {} rendition {}",
        key.jobRef().jobId(),
        key.jobRef().generation(),
        key.renditionLabel());
    return new TranscodeException(TranscodeException.GENERIC_MESSAGE);
  }

  @Override
  public void stopProcess(FfmpegProcessKey key) {
    var managed = processes.get(key);
    stopProcesses(managed == null ? List.of() : List.of(Map.entry(key, managed)), key.jobRef());
  }

  @Override
  public void stopJob(TranscodeJobRef jobRef) {
    var entriesToStop =
        processes.entrySet().stream()
            .filter(entry -> entry.getKey().jobRef().equals(jobRef))
            .toList();
    stopProcesses(entriesToStop, jobRef);
  }

  private void stopProcesses(
      List<? extends Map.Entry<FfmpegProcessKey, ManagedProcess>> entriesToStop,
      TranscodeJobRef jobRef) {
    var failures = new ArrayList<RuntimeException>();
    var interrupted = !entriesToStop.isEmpty() && Thread.interrupted();

    for (var entry : entriesToStop) {
      entry.getValue().requestStop();
      try {
        shutdownManagedProcess(entry.getValue(), jobRef);
      } catch (RuntimeException failure) {
        failures.add(failure);
        interrupted |= recoverInterruptedStop(entry.getKey(), entry.getValue(), failure);
      } finally {
        if (!entry.getValue().process().isAlive()) {
          retainTerminalObservation(entry.getKey(), entry.getValue());
        }
      }
    }

    if (!entriesToStop.isEmpty()) {
      log.info(
          "Stopped FFmpeg process(es) for job {} generation {}",
          jobRef.jobId(),
          jobRef.generation());
    }

    if (interrupted) {
      Thread.currentThread().interrupt();
      if (failures.isEmpty()) {
        failures.add(new TranscodeException(TranscodeException.GENERIC_MESSAGE));
      }
    }

    if (!failures.isEmpty()) {
      var first = failures.getFirst();
      failures.stream().skip(1).forEach(first::addSuppressed);
      throw first;
    }
  }

  private boolean recoverInterruptedStop(
      FfmpegProcessKey key, ManagedProcess managed, RuntimeException failure) {
    if (!Thread.interrupted()) {
      return false;
    }

    try {
      forceStop(key, managed);
    } catch (RuntimeException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
      Thread.interrupted();
    }
    return true;
  }

  @Override
  public void forceStopAll() {
    var failures = new ArrayList<RuntimeException>();
    for (var entry : List.copyOf(processes.entrySet())) {
      entry.getValue().requestStop();
      try {
        forceStop(entry.getKey(), entry.getValue());
      } catch (RuntimeException exception) {
        failures.add(exception);
      }
    }
    if (!failures.isEmpty()) {
      throw failures.getFirst();
    }
  }

  private void forceStop(FfmpegProcessKey key, ManagedProcess managed) {
    try {
      if (managed.process().isAlive()) {
        managed.process().destroyForcibly();
        if (!managed.process().waitFor(GRACEFUL_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
          throw new TranscodeException(TranscodeException.GENERIC_MESSAGE);
        }
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new TranscodeException(TranscodeException.GENERIC_MESSAGE, exception);
    } finally {
      if (!managed.process().isAlive()) {
        retainTerminalObservation(key, managed);
      }
    }
  }

  private void shutdownManagedProcess(ManagedProcess managed, TranscodeJobRef jobRef) {
    if (managed == null) {
      return;
    }

    if (!managed.process().isAlive()) {
      managed.drainer().close();
      return;
    }

    try {
      sendQuitSignal(managed.process(), jobRef);
      awaitShutdown(managed.process(), jobRef);
    } finally {
      if (!managed.process().isAlive()) {
        managed.drainer().close();
      }
    }
  }

  private void sendQuitSignal(Process process, TranscodeJobRef jobRef) {
    try {
      var outputStream = process.getOutputStream();
      outputStream.write('q');
      outputStream.flush();
    } catch (IOException _) {
      log.debug("Failed to write quit signal to FFmpeg stdin for job {}", jobRef.jobId());
    }
  }

  private void awaitShutdown(Process process, TranscodeJobRef jobRef) {
    try {
      if (process.waitFor(GRACEFUL_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
        return;
      }

      log.warn("FFmpeg did not stop gracefully for job {}, forcing", jobRef.jobId());
      process.destroyForcibly();
      if (!process.waitFor(GRACEFUL_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
        throw new TranscodeException(TranscodeException.GENERIC_MESSAGE);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new TranscodeException(TranscodeException.GENERIC_MESSAGE, exception);
    }
  }

  @Override
  public boolean isRunning(TranscodeJobRef jobRef) {
    return processes.entrySet().stream()
        .filter(entry -> entry.getKey().jobRef().equals(jobRef))
        .anyMatch(entry -> observe(entry.getKey()).state() == FfmpegProcessState.RUNNING);
  }

  @Override
  public boolean isRunning(FfmpegProcessKey key) {
    return observe(key).state() == FfmpegProcessState.RUNNING;
  }

  @Override
  public FfmpegProcessObservation observe(FfmpegProcessKey key) {
    var managed = processes.get(key);
    if (managed == null) {
      return terminalObservations.getOrDefault(
          key, FfmpegProcessObservation.withoutExitCode(FfmpegProcessState.ABSENT));
    }
    if (managed.process().isAlive()) {
      return FfmpegProcessObservation.withoutExitCode(FfmpegProcessState.RUNNING);
    }

    return retainTerminalObservation(key, managed);
  }

  private FfmpegProcessObservation retainTerminalObservation(
      FfmpegProcessKey key, ManagedProcess managed) {
    if (!managed.stopRequested().get()) {
      logExitDetails(key, managed);
    }
    managed.drainer().close();
    var exitCode = managed.process().exitValue();
    var state = terminalState(managed, exitCode);
    var observation = FfmpegProcessObservation.withExitCode(state, exitCode);
    terminalObservations.put(key, observation);
    processes.remove(key, managed);
    return observation;
  }

  private FfmpegProcessState terminalState(ManagedProcess managed, int exitCode) {
    if (managed.stopRequested().get()) {
      return FfmpegProcessState.STOPPED;
    }
    return exitCode == 0 ? FfmpegProcessState.COMPLETED : FfmpegProcessState.FAILED;
  }

  private void logExitDetails(FfmpegProcessKey key, ManagedProcess managed) {
    try {
      var exitCode = managed.process().exitValue();
      var recentLines = managed.drainer().getRecentOutput();
      if (!recentLines.isEmpty()) {
        var stderr = String.join("\n", recentLines);
        log.warn(
            "FFmpeg exited with code {} for job {} generation {} rendition {}: {}",
            exitCode,
            key.jobRef().jobId(),
            key.jobRef().generation(),
            key.renditionLabel(),
            stderr.substring(0, Math.min(stderr.length(), 2000)));
      }
    } catch (Exception _) {
      log.debug("Could not read FFmpeg exit details for job {}", key.jobRef().jobId());
    }
  }
}
