package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class FakeTranscodeExecutor implements TranscodeExecutor {

  private record ProcessKey(UUID sessionId, String variantLabel) {}

  private final Set<ProcessKey> running = ConcurrentHashMap.newKeySet();
  private final Set<ProcessKey> started = ConcurrentHashMap.newKeySet();
  private final Set<UUID> stopped = ConcurrentHashMap.newKeySet();
  private final Set<UUID> failingOnStop = ConcurrentHashMap.newKeySet();
  private final List<TranscodeRequest> startedRequests =
      Collections.synchronizedList(new ArrayList<>());
  private final List<ExecutionTargetId> startedTargets =
      Collections.synchronizedList(new ArrayList<>());
  private final List<String> stoppedVariants = Collections.synchronizedList(new ArrayList<>());
  private final Set<ExecutionTargetId> refusingTargets = ConcurrentHashMap.newKeySet();
  private Set<ExecutionTargetId> executionTargets =
      new LinkedHashSet<>(Set.of(ExecutionTargetId.LOCAL));
  private final AtomicLong livenessChecks = new AtomicLong();
  private int availableSlots = TranscodeExecutor.UNBOUNDED_SLOTS;
  private boolean healthy = true;
  private volatile boolean failUntargetedStarts;

  @Override
  public TranscodeHandle start(TranscodeRequest request) {
    if (failUntargetedStarts) {
      throw new TranscodeException("No connected transcode worker can run this variant");
    }
    return doStart(request);
  }

  @Override
  public TranscodeHandle start(TranscodeRequest request, ExecutionTargetId target) {
    if (refusingTargets.contains(target)) {
      throw new TranscodeException("Target " + target.value() + " refused the transcode");
    }
    startedTargets.add(target);
    return doStart(request);
  }

  private TranscodeHandle doStart(TranscodeRequest request) {
    startedRequests.add(request);
    var key = new ProcessKey(request.sessionId(), request.variantLabel());
    running.add(key);
    started.add(key);
    return new TranscodeHandle(
        1L, request.attemptId(), TranscodeStatus.ACTIVE, request.startSequenceNumber());
  }

  /**
   * Untargeted (first-fit) starts fail as when no worker is connected; targeted starts still honor
   * per-target refusals independently.
   */
  public void failUntargetedStarts() {
    failUntargetedStarts = true;
  }

  @Override
  public void stop(UUID sessionId) {
    if (failingOnStop.contains(sessionId)) {
      throw new TranscodeException("Simulated stop failure for session: " + sessionId);
    }
    running.removeIf(key -> key.sessionId().equals(sessionId));
    stopped.add(sessionId);
  }

  @Override
  public void stopVariant(UUID sessionId, String variantLabel) {
    running.remove(new ProcessKey(sessionId, variantLabel));
    stoppedVariants.add(sessionId + "/" + variantLabel);
  }

  public List<String> getStoppedVariants() {
    return List.copyOf(stoppedVariants);
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
    livenessChecks.incrementAndGet();
    return running.contains(new ProcessKey(sessionId, variantLabel));
  }

  /**
   * Per-variant liveness checks observed so far. The delivery coordinator calls this once per poll
   * iteration (after {@code syncProgress}), so awaiting an increment is a deterministic signal that
   * a poll cycle has completed — replacing wall-clock sleeps in timing tests.
   */
  public long livenessChecks() {
    return livenessChecks.get();
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

  @Override
  public Set<ExecutionTargetId> executionTargets() {
    return new LinkedHashSet<>(executionTargets);
  }

  public void setExecutionTargets(List<ExecutionTargetId> targets) {
    executionTargets = new LinkedHashSet<>(targets);
  }

  public void refuseTarget(ExecutionTargetId target) {
    refusingTargets.add(target);
  }

  public void acceptTarget(ExecutionTargetId target) {
    refusingTargets.remove(target);
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

  public List<ExecutionTargetId> getStartedTargets() {
    return List.copyOf(startedTargets);
  }

  public void reset() {
    running.clear();
    started.clear();
    stopped.clear();
    startedRequests.clear();
    startedTargets.clear();
  }
}
