package com.streamarr.server.fakes;

import com.streamarr.transcode.engine.ffmpeg.FfmpegProcessKey;
import com.streamarr.transcode.engine.ffmpeg.FfmpegProcessManager;
import com.streamarr.transcode.engine.ffmpeg.FfmpegProcessObservation;
import com.streamarr.transcode.engine.ffmpeg.FfmpegProcessState;
import com.streamarr.transcode.engine.model.TranscodeJobRef;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class FakeFfmpegProcessManager implements FfmpegProcessManager {

  private final Set<FfmpegProcessKey> running = new HashSet<>();
  private final Map<FfmpegProcessKey, FfmpegProcessObservation> terminalObservations =
      new HashMap<>();
  private final Set<UUID> started = new HashSet<>();
  private final Set<UUID> stopped = new HashSet<>();

  @Override
  public Process startProcess(FfmpegProcessKey key, List<String> command, Path workingDir) {
    running.add(key);
    terminalObservations.remove(key);
    started.add(key.jobRef().jobId());
    return new StubProcess();
  }

  @Override
  public void stopProcess(FfmpegProcessKey key) {
    retainStopped(key);
    stopped.add(key.jobRef().jobId());
  }

  @Override
  public void stopJob(TranscodeJobRef jobRef) {
    running.stream()
        .filter(key -> key.jobRef().equals(jobRef))
        .toList()
        .forEach(this::retainStopped);
    stopped.add(jobRef.jobId());
  }

  @Override
  public void forceStopAll() {
    List.copyOf(running).forEach(this::retainStopped);
  }

  @Override
  public boolean isRunning(TranscodeJobRef jobRef) {
    return running.stream().anyMatch(key -> key.jobRef().equals(jobRef));
  }

  @Override
  public boolean isRunning(FfmpegProcessKey key) {
    return running.contains(key);
  }

  @Override
  public FfmpegProcessObservation observe(FfmpegProcessKey key) {
    if (running.contains(key)) {
      return FfmpegProcessObservation.withoutExitCode(FfmpegProcessState.RUNNING);
    }
    return terminalObservations.getOrDefault(
        key, FfmpegProcessObservation.withoutExitCode(FfmpegProcessState.ABSENT));
  }

  private void retainStopped(FfmpegProcessKey key) {
    if (running.remove(key)) {
      terminalObservations.put(
          key, FfmpegProcessObservation.withExitCode(FfmpegProcessState.STOPPED, 0));
    }
  }

  public Set<UUID> getStarted() {
    return Set.copyOf(started);
  }

  public Set<UUID> getStopped() {
    return Set.copyOf(stopped);
  }

  private static class StubProcess extends Process {

    @Override
    public OutputStream getOutputStream() {
      return new ByteArrayOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
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
      // no-op for test fake
    }

    @Override
    public long pid() {
      return 1L;
    }
  }
}
