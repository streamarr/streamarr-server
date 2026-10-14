package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

public class FakeTranscodeExecutor implements TranscodeExecutor {

  private static final long OBSERVATION_TIMEOUT_SECONDS = 5;

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
  private final Map<ExecutionTargetId, Set<String>> encodableVideoCodecFamiliesByTarget =
      new ConcurrentHashMap<>();
  private final Map<ExecutionTargetId, Map<String, WorkerVerifiedEncoder>> encodersByTarget =
      new ConcurrentHashMap<>();
  private final AtomicLong livenessChecks = new AtomicLong();
  private final Object observationMonitor = new Object();
  private int availableSlots = TranscodeExecutor.UNBOUNDED_SLOTS;
  private final Map<String, Integer> encodeSlotsByVideoCodecFamily =
      new ConcurrentHashMap<>(unboundedEncodeSlots(Set.of("h264", "hevc", "av1")));
  private boolean healthy = true;
  private volatile boolean failUntargetedStarts;

  @Override
  public TranscodeHandle start(TranscodeRequest request) {
    if (failUntargetedStarts) {
      throw new TranscodeException("No connected transcode worker can run this variant");
    }

    var accepting = acceptingTarget(request);
    if (request.pinnedEncoderBackend().isPresent() && accepting.isEmpty()) {
      throw new TranscodeException("No connected transcode worker runs the pinned encoder backend");
    }

    var handle = doStart(request);
    return accepting
        .flatMap(target -> advertisedEncoder(target, request.transcodeDecision()))
        .map(handle::withEncoder)
        .orElse(handle);
  }

  // The first target, in failover order, that advertises an encoder the request can run on.
  private Optional<ExecutionTargetId> acceptingTarget(TranscodeRequest request) {
    var candidates =
        request
            .pinnedEncoderBackend()
            .map(this::executionTargets)
            .orElseGet(() -> executionTargets(request.transcodeDecision()));
    return candidates.stream()
        .filter(target -> advertisedEncoder(target, request.transcodeDecision()).isPresent())
        .findFirst();
  }

  @Override
  public TranscodeHandle start(TranscodeRequest request, ExecutionTargetId target) {
    if (refusingTargets.contains(target) || !canRun(target, request.transcodeDecision())) {
      throw new TranscodeException("Target " + target.value() + " refused the transcode");
    }
    startedTargets.add(target);
    var handle = doStart(request);
    return advertisedEncoder(target, request.transcodeDecision())
        .map(handle::withEncoder)
        .orElse(handle);
  }

  private Optional<WorkerVerifiedEncoder> advertisedEncoder(
      ExecutionTargetId target, TranscodeDecision decision) {
    if (!encodesVideo(decision)) {
      return Optional.empty();
    }

    return Optional.ofNullable(
        encodersByTarget.getOrDefault(target, Map.of()).get(decision.videoCodecFamily()));
  }

  private TranscodeHandle doStart(TranscodeRequest request) {
    if (encodesVideo(request.transcodeDecision()) && !tryTakeEncodeSlot(request)) {
      throw new TranscodeException("No connected transcode worker can encode this variant");
    }

    startedRequests.add(request);
    var key = new ProcessKey(request.sessionId(), request.variantLabel());
    running.add(key);
    started.add(key);
    var handle =
        new TranscodeHandle(
            1L, request.attemptId(), TranscodeStatus.ACTIVE, request.startSequenceNumber());
    signalObservation();
    return handle;
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
  public boolean isRunning(UUID sessionId, String variantLabel) {
    livenessChecks.incrementAndGet();
    var result = running.contains(new ProcessKey(sessionId, variantLabel));
    signalObservation();
    return result;
  }

  /**
   * Per-variant liveness checks observed so far. The delivery coordinator calls this once per poll
   * iteration (after {@code syncProgress}), so awaiting an increment is a deterministic signal that
   * a poll cycle has completed — replacing wall-clock sleeps in timing tests.
   */
  public long livenessChecks() {
    return livenessChecks.get();
  }

  public void awaitLivenessCheckCount(long count) {
    awaitObservation(() -> livenessChecks.get() >= count, "liveness check count " + count);
  }

  public void awaitStartedRequestCount(int count) {
    awaitObservation(() -> startedRequests.size() >= count, "started request count " + count);
  }

  public void awaitStartedTargetCount(int count) {
    awaitObservation(() -> startedTargets.size() >= count, "started target count " + count);
  }

  public void awaitStartedTarget(ExecutionTargetId target) {
    awaitObservation(() -> startedTargets.contains(target), "started target " + target.value());
  }

  private void signalObservation() {
    synchronized (observationMonitor) {
      observationMonitor.notifyAll();
    }
  }

  private void awaitObservation(BooleanSupplier observed, String description) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(OBSERVATION_TIMEOUT_SECONDS);
    synchronized (observationMonitor) {
      while (!observed.getAsBoolean()) {
        var remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          throw new AssertionError("Timed out waiting for " + description);
        }
        try {
          TimeUnit.NANOSECONDS.timedWait(observationMonitor, remaining);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError("Interrupted while waiting for " + description, e);
        }
      }
    }
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

  @Override
  public int availableSlots(TranscodeDecision decision) {
    if (!encodesVideo(decision)) {
      return availableSlots;
    }

    var encodeSlots = encodeSlotsByVideoCodecFamily.getOrDefault(decision.videoCodecFamily(), 0);
    return Math.min(availableSlots, encodeSlots);
  }

  public void setAvailableSlots(int availableSlots) {
    this.availableSlots = availableSlots;
  }

  @Override
  public Set<ExecutionTargetId> executionTargets(TranscodeDecision decision) {
    return executionTargets.stream()
        .filter(target -> canRun(target, decision))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  @Override
  public Set<ExecutionTargetId> executionTargets(EncoderBackend pinnedEncoderBackend) {
    return executionTargets.stream()
        .filter(target -> advertises(target, pinnedEncoderBackend))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private boolean advertises(ExecutionTargetId target, EncoderBackend backend) {
    var encoder = encodersByTarget.getOrDefault(target, Map.of()).get(backend.codecFamily());
    return encoder != null && encoder.backend().equals(backend);
  }

  /**
   * The target advertises these worker-verified encoders, one for each codec family, and refuses an
   * encode of any other family. Starts on the target report its encoder for the codec family.
   */
  public void setTargetEncoders(ExecutionTargetId target, List<WorkerVerifiedEncoder> encoders) {
    encodersByTarget.put(
        target,
        encoders.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    encoder -> encoder.backend().codecFamily(), Function.identity())));
    setTargetEncodableVideoCodecFamilies(
        target,
        encoders.stream()
            .map(encoder -> encoder.backend().codecFamily())
            .collect(Collectors.toUnmodifiableSet()));
  }

  public void setExecutionTargets(List<ExecutionTargetId> targets) {
    executionTargets = new LinkedHashSet<>(targets);
  }

  /** The target refuses an encode of any other family. A target left unset encodes every one. */
  public void setTargetEncodableVideoCodecFamilies(ExecutionTargetId target, Set<String> families) {
    encodableVideoCodecFamiliesByTarget.put(target, Set.copyOf(families));
  }

  private boolean canRun(ExecutionTargetId target, TranscodeDecision decision) {
    var families = encodableVideoCodecFamiliesByTarget.get(target);
    return !encodesVideo(decision)
        || families == null
        || families.contains(decision.videoCodecFamily());
  }

  @Override
  public Set<String> encodableVideoCodecFamilies() {
    return encodeSlotsByVideoCodecFamily.entrySet().stream()
        .filter(entry -> entry.getValue() > 0)
        .map(Map.Entry::getKey)
        .collect(Collectors.toUnmodifiableSet());
  }

  /** Every listed family gets unbounded encode slots; any other family has none. */
  public void setEncodableVideoCodecFamilies(Set<String> families) {
    setEncodeSlots(unboundedEncodeSlots(families));
  }

  /**
   * Free encode slots for each video codec family, as if each family's encoder ran on its own
   * worker. Each started encode takes one slot from its family.
   */
  public void setEncodeSlots(Map<String, Integer> slotsByVideoCodecFamily) {
    encodeSlotsByVideoCodecFamily.clear();
    encodeSlotsByVideoCodecFamily.putAll(slotsByVideoCodecFamily);
  }

  private synchronized boolean tryTakeEncodeSlot(TranscodeRequest request) {
    var family = request.transcodeDecision().videoCodecFamily();
    var slots = encodeSlotsByVideoCodecFamily.getOrDefault(family, 0);
    if (slots <= 0) {
      return false;
    }

    if (slots != UNBOUNDED_SLOTS) {
      encodeSlotsByVideoCodecFamily.put(family, slots - 1);
    }

    return true;
  }

  private static Map<String, Integer> unboundedEncodeSlots(Set<String> families) {
    return families.stream().collect(Collectors.toMap(Function.identity(), _ -> UNBOUNDED_SLOTS));
  }

  private static boolean encodesVideo(TranscodeDecision decision) {
    return switch (decision.transcodeMode()) {
      case VIDEO_TRANSCODE, FULL_TRANSCODE -> true;
      case REMUX, AUDIO_TRANSCODE -> false;
    };
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
