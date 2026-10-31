package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeFailure;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Collection;
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
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FakeTranscodeExecutor implements TranscodeExecutor {

  private static final long OBSERVATION_TIMEOUT_SECONDS = 5;

  private record ProcessKey(UUID sessionId, String variantLabel) {}

  private final Set<ProcessKey> running = ConcurrentHashMap.newKeySet();
  private final Map<ProcessKey, ExecutionTargetId> runningTargets = new ConcurrentHashMap<>();
  private final Set<ProcessKey> started = ConcurrentHashMap.newKeySet();
  private final Set<UUID> stopped = ConcurrentHashMap.newKeySet();
  private final Map<UUID, TranscodeFailure> terminalFailures = new ConcurrentHashMap<>();
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
  private final AtomicLong jobSlotRefusals = new AtomicLong();
  private final Map<ProcessKey, AtomicLong> livenessChecksByVariant = new ConcurrentHashMap<>();
  private final Set<UUID> failingOnStopVariant = ConcurrentHashMap.newKeySet();
  private final Set<ProcessKey> endingAfterNextLivenessCheck = ConcurrentHashMap.newKeySet();
  private final HeldSegmentRequests heldRequests =
      new HeldSegmentRequests(new SimpleMeterRegistry());
  private final Object observationMonitor = new Object();
  private int availableSlots = TranscodeExecutor.UNBOUNDED_SLOTS;
  private volatile int jobSlots = TranscodeExecutor.UNBOUNDED_SLOTS;
  private final Map<ExecutionTargetId, Integer> jobSlotsByTarget = new ConcurrentHashMap<>();
  private final Map<String, Integer> encodeSlotsByVideoCodecFamily =
      new ConcurrentHashMap<>(unboundedEncodeSlots(Set.of("h264", "hevc", "av1")));
  private Set<String> hardwareEncodableVideoCodecFamilies = Set.of();
  private boolean healthy = true;
  private volatile boolean failUntargetedStarts;

  @Override
  public TranscodeHandle start(TranscodeRequest request) {
    if (failUntargetedStarts) {
      throw new TranscodeException("No connected transcode worker can run this variant");
    }

    var accepting = acceptingTarget(request);
    if (request.pinnedEncoder().isPresent() && accepting.isEmpty()) {
      throw new TranscodeException("No connected transcode worker runs the pinned encoder");
    }

    // A start that no target advertises an encoder for runs on an eligible target.
    var runningTarget = accepting.or(() -> firstWithFreeJobSlot(candidateTargets(request)));
    var handle = doStart(request, runningTarget);
    return accepting
        .flatMap(target -> advertisedEncoder(target, request.transcodeDecision()))
        .map(handle::withEncoder)
        .orElse(handle);
  }

  // The first target, in failover order, that advertises an encoder the request can run on.
  private Optional<ExecutionTargetId> acceptingTarget(TranscodeRequest request) {
    return firstWithFreeJobSlot(
        candidateTargets(request).stream()
            .filter(target -> advertisedEncoder(target, request.transcodeDecision()).isPresent())
            .toList());
  }

  // Prefers a target with a free job slot. When every target is full, the first refuses the start.
  private Optional<ExecutionTargetId> firstWithFreeJobSlot(Collection<ExecutionTargetId> targets) {
    return targets.stream()
        .filter(target -> freeJobSlotsOn(target) > 0)
        .findFirst()
        .or(() -> targets.stream().findFirst());
  }

  private Set<ExecutionTargetId> candidateTargets(TranscodeRequest request) {
    return request
        .pinnedEncoder()
        .map(this::executionTargets)
        .orElseGet(() -> executionTargets(request.transcodeDecision()));
  }

  @Override
  public TranscodeHandle start(TranscodeRequest request, ExecutionTargetId target) {
    if (refusingTargets.contains(target)
        || !canRun(target, request.transcodeDecision())
        || !request.pinnedEncoder().map(pinned -> advertises(target, pinned)).orElse(true)) {
      throw new TranscodeException("Target " + target.value() + " refused the transcode");
    }
    var handle = doStart(request, Optional.of(target));
    // A recorded start names a job that already runs, so a test that awaits it can end the job.
    startedTargets.add(target);
    signalObservation();
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

  private TranscodeHandle doStart(
      TranscodeRequest request, Optional<ExecutionTargetId> runningTarget) {
    if (encodesVideo(request.transcodeDecision()) && !tryTakeEncodeSlot(request)) {
      throw new TranscodeException("No connected transcode worker can encode this variant");
    }

    var key = new ProcessKey(request.sessionId(), request.variantLabel());
    if (!tryTakeJobSlot(key, runningTarget)) {
      jobSlotRefusals.incrementAndGet();
      signalObservation();
      throw new TranscodeException("No connected transcode worker has a free job slot");
    }

    started.add(key);
    startedRequests.add(request);
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
    endAll(sessionId);
    stopped.add(sessionId);
  }

  @Override
  public void destroySession(UUID sessionId) {
    try {
      stop(sessionId);
    } finally {
      terminalFailures.remove(sessionId);
    }
  }

  @Override
  public void stopVariant(UUID sessionId, String variantLabel) {
    if (failingOnStopVariant.contains(sessionId)) {
      throw new IllegalStateException("Simulated variant stop failure for session: " + sessionId);
    }

    end(new ProcessKey(sessionId, variantLabel));
    stoppedVariants.add(sessionId + "/" + variantLabel);
  }

  /**
   * The requests held until this executor's job attempts store their segments. Like the worker
   * session server, the executor wakes a variant's held requests when one of its job attempts ends.
   */
  public HeldSegmentRequests heldSegmentRequests() {
    return heldRequests;
  }

  private void endAll(UUID sessionId) {
    running.stream().filter(key -> key.sessionId().equals(sessionId)).toList().forEach(this::end);
  }

  private void end(ProcessKey key) {
    runningTargets.remove(key);
    if (running.remove(key)) {
      heldRequests.wakeVariant(key.sessionId(), key.variantLabel());
    }
  }

  public List<String> getStoppedVariants() {
    return List.copyOf(stoppedVariants);
  }

  public void failOnStop(UUID sessionId) {
    failingOnStop.add(sessionId);
  }

  /** Every later stop of one of the session's variants throws, as a server that shuts down does. */
  public void failOnStopVariant(UUID sessionId) {
    failingOnStopVariant.add(sessionId);
  }

  @Override
  public boolean isRunning(UUID sessionId, String variantLabel) {
    var key = new ProcessKey(sessionId, variantLabel);
    livenessChecks.incrementAndGet();
    livenessChecksByVariant.computeIfAbsent(key, _ -> new AtomicLong()).incrementAndGet();
    var result = running.contains(key);
    if (endingAfterNextLivenessCheck.remove(key)) {
      end(key);
    }

    signalObservation();
    return result;
  }

  /**
   * The variant's job attempt ends right after the next look at its liveness, which still finds it
   * running, as a job attempt that reaches the end of its file between two looks does.
   */
  public void endAfterNextLivenessCheck(UUID sessionId, String variantLabel) {
    endingAfterNextLivenessCheck.add(new ProcessKey(sessionId, variantLabel));
  }

  @Override
  public Optional<ExecutionTargetId> runningExecutionTarget(UUID sessionId, String variantLabel) {
    var key = new ProcessKey(sessionId, variantLabel);
    if (!running.contains(key)) {
      return Optional.empty();
    }

    return Optional.ofNullable(runningTargets.get(key));
  }

  /**
   * Per-variant liveness checks observed so far. The delivery coordinator calls this once in each
   * pass that looks for a segment and finds none (after {@code syncProgress}), so awaiting an
   * increment is a deterministic signal that a held request has looked at its variant.
   */
  public long livenessChecks() {
    return livenessChecks.get();
  }

  public void awaitLivenessCheckCount(long count) {
    awaitObservation(() -> livenessChecks.get() >= count, "liveness check count " + count);
  }

  /**
   * The liveness checks of one variant observed so far. A request checks the liveness of other
   * variants while it applies the on-demand rules, so only a check of its own variant shows that it
   * has looked for its segment.
   */
  public long livenessChecks(UUID sessionId, String variantLabel) {
    var checks = livenessChecksByVariant.get(new ProcessKey(sessionId, variantLabel));
    if (checks == null) {
      return 0;
    }

    return checks.get();
  }

  public void awaitLivenessCheckCount(UUID sessionId, String variantLabel, long count) {
    awaitObservation(
        () -> livenessChecks(sessionId, variantLabel) >= count,
        "liveness check count " + count + " of variant " + variantLabel);
  }

  /** The starts refused so far because every job slot was taken. */
  public long jobSlotRefusals() {
    return jobSlotRefusals.get();
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
  public Optional<TranscodeFailure> terminalFailure(UUID sessionId) {
    return Optional.ofNullable(terminalFailures.get(sessionId));
  }

  public void failTerminally(UUID sessionId, TranscodeFailure failure) {
    terminalFailures.put(sessionId, failure);
    endAll(sessionId);
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
    return Math.min(Math.min(availableSlots, freeJobSlots()), freeJobSlotsOn(executionTargets));
  }

  @Override
  public int availableSlots(TranscodeDecision decision) {
    var slots = Math.min(availableSlots(), freeJobSlotsOn(executionTargets(decision)));
    if (!encodesVideo(decision)) {
      return slots;
    }

    return Math.min(slots, encodeSlots(decision.videoCodecFamily()));
  }

  @Override
  public int availableSlots(WorkerVerifiedEncoder pinnedEncoder) {
    var slots = Math.min(availableSlots(), freeJobSlotsOn(executionTargets(pinnedEncoder)));
    return Math.min(slots, encodeSlots(pinnedEncoder.backend().codecFamily()));
  }

  private int encodeSlots(String videoCodecFamily) {
    return encodeSlotsByVideoCodecFamily.getOrDefault(videoCodecFamily, 0);
  }

  public void setAvailableSlots(int availableSlots) {
    this.availableSlots = availableSlots;
  }

  /**
   * The workers have this many job slots in all. Each running job attempt takes one, and a start
   * finds no worker when every slot is taken. Ending a job attempt frees its slot.
   */
  public void limitJobSlots(int jobSlots) {
    this.jobSlots = jobSlots;
  }

  /**
   * The target has this many job slots. Each job attempt that runs on it takes one, and a start on
   * it is refused when every slot is taken. A target left unset has unbounded job slots.
   */
  public void limitJobSlots(ExecutionTargetId target, int jobSlots) {
    jobSlotsByTarget.put(target, jobSlots);
  }

  private synchronized int freeJobSlots() {
    if (jobSlots == UNBOUNDED_SLOTS) {
      return UNBOUNDED_SLOTS;
    }

    return Math.max(0, jobSlots - running.size());
  }

  private synchronized int freeJobSlotsOn(ExecutionTargetId target) {
    var limit = jobSlotsByTarget.get(target);
    if (limit == null) {
      return UNBOUNDED_SLOTS;
    }

    var taken = runningTargets.values().stream().filter(target::equals).count();
    return (int) Math.max(0, limit - taken);
  }

  private synchronized int freeJobSlotsOn(Collection<ExecutionTargetId> targets) {
    if (jobSlotsByTarget.isEmpty()) {
      return UNBOUNDED_SLOTS;
    }

    var free = targets.stream().mapToLong(this::freeJobSlotsOn).sum();
    return (int) Math.min(UNBOUNDED_SLOTS, free);
  }

  private synchronized boolean tryTakeJobSlot(
      ProcessKey key, Optional<ExecutionTargetId> runningTarget) {
    if (freeJobSlots() <= 0 || runningTarget.filter(t -> freeJobSlotsOn(t) <= 0).isPresent()) {
      return false;
    }

    running.add(key);
    runningTarget.ifPresentOrElse(
        target -> runningTargets.put(key, target), () -> runningTargets.remove(key));
    return true;
  }

  @Override
  public Set<ExecutionTargetId> executionTargets(TranscodeDecision decision) {
    return executionTargets.stream()
        .filter(target -> canRun(target, decision))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  @Override
  public Set<ExecutionTargetId> executionTargets(WorkerVerifiedEncoder pinnedEncoder) {
    return executionTargets.stream()
        .filter(target -> advertises(target, pinnedEncoder))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private boolean advertises(ExecutionTargetId target, WorkerVerifiedEncoder pinnedEncoder) {
    return pinnedEncoder.equals(
        encodersByTarget.getOrDefault(target, Map.of()).get(pinnedEncoder.backend().codecFamily()));
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
  public Set<String> verifiedVideoCodecFamilies() {
    return Set.copyOf(encodeSlotsByVideoCodecFamily.keySet());
  }

  /**
   * A family whose encoders some targets advertise is encodable only while one of those targets has
   * a free job slot, so the encoders of one target share its job slots.
   */
  @Override
  public Set<String> encodableVideoCodecFamilies() {
    return encodeSlotsByVideoCodecFamily.entrySet().stream()
        .filter(entry -> entry.getValue() > 0)
        .map(Map.Entry::getKey)
        .filter(family -> !advertisedOnlyByFullTargets(family))
        .collect(Collectors.toUnmodifiableSet());
  }

  private boolean advertisedOnlyByFullTargets(String family) {
    var advertising =
        encodersByTarget.entrySet().stream()
            .filter(entry -> entry.getValue().containsKey(family))
            .map(Map.Entry::getKey)
            .toList();
    return !advertising.isEmpty()
        && advertising.stream().allMatch(target -> freeJobSlotsOn(target) <= 0);
  }

  @Override
  public Set<String> hardwareEncodableVideoCodecFamilies() {
    var encodable = encodableVideoCodecFamilies();
    return Stream.concat(
            hardwareEncodableVideoCodecFamilies.stream(),
            advertisedHardwareFamilies(target -> freeJobSlotsOn(target) > 0))
        .filter(encodable::contains)
        .collect(Collectors.toUnmodifiableSet());
  }

  /** Busy targets' hardware encoders count too. */
  @Override
  public Set<String> verifiedHardwareVideoCodecFamilies() {
    var verified = verifiedVideoCodecFamilies();
    return Stream.concat(
            hardwareEncodableVideoCodecFamilies.stream(), advertisedHardwareFamilies(_ -> true))
        .filter(verified::contains)
        .collect(Collectors.toUnmodifiableSet());
  }

  // The families of the hardware encoders that the matching targets advertise.
  private Stream<String> advertisedHardwareFamilies(Predicate<ExecutionTargetId> targets) {
    return encodersByTarget.entrySet().stream()
        .filter(entry -> targets.test(entry.getKey()))
        .flatMap(entry -> entry.getValue().values().stream())
        .map(WorkerVerifiedEncoder::backend)
        .filter(backend -> backend.kind() == EncoderBackend.Kind.HARDWARE)
        .map(EncoderBackend::codecFamily);
  }

  /** Each listed family that has a free encode slot is encoded in hardware. */
  public void setHardwareEncodableVideoCodecFamilies(Set<String> families) {
    hardwareEncodableVideoCodecFamilies = Set.copyOf(families);
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
    endAll(sessionId);
  }

  public void markDead(UUID sessionId, String variantLabel) {
    end(new ProcessKey(sessionId, variantLabel));
  }

  public List<TranscodeRequest> getStartedRequests() {
    return List.copyOf(startedRequests);
  }

  public List<ExecutionTargetId> getStartedTargets() {
    return List.copyOf(startedTargets);
  }

  public void reset() {
    running.clear();
    runningTargets.clear();
    started.clear();
    stopped.clear();
    startedRequests.clear();
    startedTargets.clear();
  }
}
