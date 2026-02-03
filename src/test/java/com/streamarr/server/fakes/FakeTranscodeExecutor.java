package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class FakeTranscodeExecutor implements TranscodeExecutor {

  private final Set<UUID> running = new HashSet<>();
  private final Set<UUID> started = new HashSet<>();
  private final Set<UUID> stopped = new HashSet<>();

  @Override
  public TranscodeHandle start(TranscodeRequest request) {
    running.add(request.sessionId());
    started.add(request.sessionId());
    return new TranscodeHandle(1L, TranscodeStatus.ACTIVE);
  }

  @Override
  public void stop(UUID sessionId) {
    running.remove(sessionId);
    stopped.add(sessionId);
  }

  @Override
  public boolean isRunning(UUID sessionId) {
    return running.contains(sessionId);
  }

  @Override
  public boolean isHealthy() {
    return true;
  }

  public Set<UUID> getStarted() {
    return Set.copyOf(started);
  }

  public Set<UUID> getStopped() {
    return Set.copyOf(stopped);
  }

  public int getRunningCount() {
    return running.size();
  }

  public void markDead(UUID sessionId) {
    running.remove(sessionId);
  }
}
