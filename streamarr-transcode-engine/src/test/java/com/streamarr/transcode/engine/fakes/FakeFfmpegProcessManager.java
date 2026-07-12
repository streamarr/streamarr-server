package com.streamarr.transcode.engine.fakes;

import com.streamarr.transcode.engine.ffmpeg.FfmpegProcessKey;
import com.streamarr.transcode.engine.ffmpeg.FfmpegProcessManager;
import com.streamarr.transcode.engine.ffmpeg.FfmpegProcessObservation;
import com.streamarr.transcode.engine.ffmpeg.FfmpegProcessState;
import com.streamarr.transcode.engine.model.TranscodeJobRef;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class FakeFfmpegProcessManager implements FfmpegProcessManager {

  @FunctionalInterface
  public interface StartAction {

    void accept(FfmpegProcessKey key, Path outputDirectory) throws IOException;
  }

  private final List<FfmpegProcessKey> startedKeys = new CopyOnWriteArrayList<>();
  private final ConcurrentHashMap<FfmpegProcessKey, FfmpegProcessObservation> observations =
      new ConcurrentHashMap<>();
  private volatile StartAction startAction = (_, _) -> {};
  private volatile Runnable stopAction = () -> {};
  private volatile Consumer<FfmpegProcessKey> observeAction = _ -> {};
  private volatile Consumer<FfmpegProcessKey> observedAction = _ -> {};
  private final AtomicReference<RuntimeException> nextStopFailure = new AtomicReference<>();
  private final AtomicReference<RuntimeException> nextForceStopFailure = new AtomicReference<>();
  private final AtomicBoolean refuseNextRelease = new AtomicBoolean();

  public void onStart(StartAction action) {
    startAction = action;
  }

  public List<FfmpegProcessKey> startedKeys() {
    return List.copyOf(startedKeys);
  }

  public void failProcess(FfmpegProcessKey key, int exitCode) {
    observations.put(
        key, FfmpegProcessObservation.withExitCode(FfmpegProcessState.FAILED, exitCode));
  }

  public void completeProcess(FfmpegProcessKey key) {
    observations.put(key, FfmpegProcessObservation.withExitCode(FfmpegProcessState.COMPLETED, 0));
  }

  public void failNextStop(RuntimeException failure) {
    nextStopFailure.set(failure);
  }

  public void failNextForceStop(RuntimeException failure) {
    nextForceStopFailure.set(failure);
  }

  public void refuseNextRelease() {
    refuseNextRelease.set(true);
  }

  public void onStop(Runnable action) {
    stopAction = action;
  }

  public void onObserve(Consumer<FfmpegProcessKey> action) {
    observeAction = action;
  }

  public void afterObserve(Consumer<FfmpegProcessKey> action) {
    observedAction = action;
  }

  @Override
  public Process startProcess(FfmpegProcessKey key, List<String> command, Path workingDir) {
    startedKeys.add(key);
    observations.put(key, FfmpegProcessObservation.withoutExitCode(FfmpegProcessState.RUNNING));
    try {
      startAction.accept(key, workingDir);
    } catch (IOException exception) {
      startedKeys.remove(key);
      observations.remove(key);
      throw new UncheckedIOException(exception);
    } catch (RuntimeException exception) {
      startedKeys.remove(key);
      observations.remove(key);
      throw exception;
    }
    return new StubProcess();
  }

  @Override
  public void stopProcess(FfmpegProcessKey key) {
    observations.put(key, FfmpegProcessObservation.withExitCode(FfmpegProcessState.STOPPED, 0));
  }

  @Override
  public void stopJob(TranscodeJobRef jobRef) {
    var failure = nextStopFailure.getAndSet(null);
    if (failure != null) {
      throw failure;
    }
    stopAction.run();
    observations.replaceAll(
        (key, observation) -> key.jobRef().equals(jobRef) ? stoppedObservation() : observation);
  }

  @Override
  public void forceStopAll() {
    var failure = nextForceStopFailure.getAndSet(null);
    if (failure != null) {
      throw failure;
    }
    observations.replaceAll((_, _) -> stoppedObservation());
  }

  @Override
  public boolean isRunning(TranscodeJobRef jobRef) {
    return observations.entrySet().stream()
        .anyMatch(
            entry ->
                entry.getKey().jobRef().equals(jobRef)
                    && entry.getValue().state() == FfmpegProcessState.RUNNING);
  }

  @Override
  public boolean isRunning(FfmpegProcessKey key) {
    return observe(key).state() == FfmpegProcessState.RUNNING;
  }

  @Override
  public FfmpegProcessObservation observe(FfmpegProcessKey key) {
    observeAction.accept(key);
    var observation =
        observations.getOrDefault(
            key, FfmpegProcessObservation.withoutExitCode(FfmpegProcessState.ABSENT));
    observedAction.accept(key);
    return observation;
  }

  @Override
  public boolean releaseJobObservation(TranscodeJobRef jobRef) {
    if (refuseNextRelease.getAndSet(false)) {
      return false;
    }
    if (isRunning(jobRef)) {
      return false;
    }
    observations.keySet().removeIf(key -> key.jobRef().equals(jobRef));
    return true;
  }

  private static FfmpegProcessObservation stoppedObservation() {
    return FfmpegProcessObservation.withExitCode(FfmpegProcessState.STOPPED, 0);
  }

  private static final class StubProcess extends Process {

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {
      // The stub represents an already-completed process, so destruction has no further effect.
    }
  }
}
