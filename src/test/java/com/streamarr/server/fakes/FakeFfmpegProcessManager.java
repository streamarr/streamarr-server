package com.streamarr.server.fakes;

import com.streamarr.server.services.streaming.ffmpeg.FfmpegProcessManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class FakeFfmpegProcessManager implements FfmpegProcessManager {

  private record ProcessKey(UUID sessionId, String variantLabel) {}

  private final Set<ProcessKey> running = new HashSet<>();
  private final Set<UUID> started = new HashSet<>();
  private final Set<UUID> stopped = new HashSet<>();

  @Override
  public Process startProcess(
      UUID sessionId, String variantLabel, List<String> command, Path workingDir) {
    running.add(new ProcessKey(sessionId, variantLabel));
    started.add(sessionId);
    return new StubProcess();
  }

  @Override
  public void stopProcess(UUID sessionId) {
    running.removeIf(key -> key.sessionId().equals(sessionId));
    stopped.add(sessionId);
  }

  @Override
  public boolean isRunning(UUID sessionId) {
    return running.stream().anyMatch(key -> key.sessionId().equals(sessionId));
  }

  @Override
  public boolean isRunning(UUID sessionId, String variantLabel) {
    return running.contains(new ProcessKey(sessionId, variantLabel));
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
    public void destroy() {}

    @Override
    public long pid() {
      return 1L;
    }
  }
}
