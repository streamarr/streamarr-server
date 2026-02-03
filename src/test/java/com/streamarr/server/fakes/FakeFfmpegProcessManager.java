package com.streamarr.server.fakes;

import com.streamarr.server.services.streaming.ffmpeg.FfmpegProcessManager;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class FakeFfmpegProcessManager implements FfmpegProcessManager {

  private final Set<UUID> running = new HashSet<>();
  private final Set<UUID> started = new HashSet<>();
  private final Set<UUID> stopped = new HashSet<>();

  @Override
  public Process startProcess(UUID sessionId, List<String> command, Path workingDir) {
    running.add(sessionId);
    started.add(sessionId);
    return null;
  }

  @Override
  public void stopProcess(UUID sessionId) {
    running.remove(sessionId);
    stopped.add(sessionId);
  }

  @Override
  public boolean isRunning(UUID sessionId) {
    return running.contains(sessionId);
  }

  public Set<UUID> getStarted() {
    return Set.copyOf(started);
  }

  public Set<UUID> getStopped() {
    return Set.copyOf(stopped);
  }
}
