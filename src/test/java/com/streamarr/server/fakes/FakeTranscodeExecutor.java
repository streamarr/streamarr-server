package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class FakeTranscodeExecutor implements TranscodeExecutor {

  private record ProcessKey(UUID sessionId, String variantLabel) {}

  private final Set<ProcessKey> running = new HashSet<>();
  private final Set<ProcessKey> started = new HashSet<>();
  private final Set<UUID> stopped = new HashSet<>();
  private final Set<UUID> failingOnStop = new HashSet<>();
  private final List<TranscodeRequest> startedRequests = new ArrayList<>();
  private int availableSlots = Integer.MAX_VALUE;
  private boolean healthy = true;

  @Override
  public TranscodeHandle start(TranscodeRequest request) {
    startedRequests.add(request);
    var key = new ProcessKey(request.sessionId(), request.variantLabel());
    running.add(key);
    started.add(key);
    return new TranscodeHandle(1L, TranscodeStatus.ACTIVE, request.startSequenceNumber());
  }

  @Override
  public void stop(UUID sessionId) {
    if (failingOnStop.contains(sessionId)) {
      throw new TranscodeException("Simulated stop failure for session: " + sessionId);
    }
    running.removeIf(key -> key.sessionId().equals(sessionId));
    stopped.add(sessionId);
  }

  public void failOnStop(UUID sessionId) {
    failingOnStop.add(sessionId);
  }

  @Override
  public boolean isRunning(UUID sessionId) {
    return running.stream().anyMatch(key -> key.sessionId().equals(sessionId));
  }

  @Override
  public boolean isRunning(UUID sessionId, String variantLabel) {
    return running.contains(new ProcessKey(sessionId, variantLabel));
  }

  @Override
  public boolean isHealthy() {
    return healthy;
  }

  public void setHealthy(boolean healthy) {
    this.healthy = healthy;
  }

  @Override
  public int availableSlots() {
    return availableSlots;
  }

  public void setAvailableSlots(int availableSlots) {
    this.availableSlots = availableSlots;
  }

  public Set<UUID> getStarted() {
    return started.stream().map(ProcessKey::sessionId).collect(Collectors.toUnmodifiableSet());
  }

  public Set<String> getStartedVariants() {
    return started.stream().map(ProcessKey::variantLabel).collect(Collectors.toUnmodifiableSet());
  }

  public Set<UUID> getStopped() {
    return Set.copyOf(stopped);
  }

  public int getRunningCount() {
    return running.size();
  }

  public void markDead(UUID sessionId) {
    running.removeIf(key -> key.sessionId().equals(sessionId));
  }

  public void markDead(UUID sessionId, String variantLabel) {
    running.remove(new ProcessKey(sessionId, variantLabel));
  }

  public List<TranscodeRequest> getStartedRequests() {
    return List.copyOf(startedRequests);
  }

  public void reset() {
    running.clear();
    started.clear();
    stopped.clear();
    startedRequests.clear();
  }
}
