package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.streaming.ProducerLifecycleService.ExhaustResult;
import com.streamarr.server.services.streaming.ProducerLifecycleService.ReplaceProducerCommand;
import com.streamarr.server.services.streaming.ProducerLifecycleService.ReplaceResult;
import com.streamarr.server.services.streaming.ProducerLifecycleService.ReplacementReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the segment wait loop and the recovery cycle (ADR 0019, issue #252). A request for an
 * advertised-but-missing segment waits on producer liveness and publication progress — never a wall
 * clock — and a dead or stalled producer is replaced at the requested segment's offset across a
 * snapshot of execution targets. Only when every snapshotted target has been tried does the variant
 * become terminally {@code FAILED}; a target outside that snapshot, or a genuine seek, revives it.
 *
 * <p>Coordinator state is bookkeeping only, guarded by each variant state's own monitor. Producer
 * mutation is serialized by {@link ProducerLifecycleService}'s per-session mutex, whose atomic
 * predicate guarantees at most one producer start per death regardless of concurrent waiters.
 */
@Slf4j
@Builder
public class SegmentDeliveryCoordinator {

  private final RuntimeStreamSessionRegistry runtimeRegistry;
  private final SegmentStore segmentStore;
  private final TranscodeExecutor transcodeExecutor;
  private final ProducerLifecycleService producerLifecycle;
  private final StreamingProperties properties;
  private final Clock clock;

  @Builder.Default private final Duration pollInterval = Duration.ofMillis(100);

  @Builder.Default
  private final ConcurrentHashMap<VariantKey, VariantDeliveryState> states =
      new ConcurrentHashMap<>();

  public SegmentDelivery deliver(UUID sessionId, String variantLabel, String segmentName) {
    while (true) {
      var outcome = deliverOnce(sessionId, variantLabel, segmentName);
      if (outcome != null) {
        return outcome;
      }
      // Every non-terminal pass — waiting on a live producer, a superseded recovery attempt, or a
      // FAILED variant awaiting revival — re-observes at poll cadence, never in a hot loop. The
      // loop owns the one wait and the one interrupt check.
      if (!sleepOnePoll()) {
        return new SegmentDelivery.Cancelled();
      }
    }
  }

  /**
   * One delivery pass. Returns a terminal {@link SegmentDelivery} outcome, or {@code null} to mean
   * "re-observe after one poll interval".
   */
  private SegmentDelivery deliverOnce(UUID sessionId, String variantLabel, String segmentName) {
    var ready = tryRead(sessionId, segmentName);
    if (ready != null) {
      return ready;
    }

    var session = runtimeRegistry.findById(sessionId).orElse(null);
    if (session == null) {
      return new SegmentDelivery.SessionEnded();
    }

    var handle = session.getVariantHandle(variantLabel);
    if (handle == null) {
      return new SegmentDelivery.SessionEnded();
    }

    // Bookkeeping is created only for validated requests, so post-destroy retries cannot
    // re-grow state that forgetSession already dropped.
    var state =
        states.computeIfAbsent(
            new VariantKey(sessionId, variantLabel), _ -> new VariantDeliveryState());

    if (handle.status() == TranscodeStatus.FAILED) {
      return resolveFailedVariant(
          state, session, handle, pendingSegment(sessionId, variantLabel, segmentName, handle));
    }

    var positioned = tryEnsurePositioned(session, segmentName);
    handle = session.getVariantHandle(variantLabel);
    if (handle == null) {
      return new SegmentDelivery.SessionEnded();
    }

    var pending = pendingSegment(sessionId, variantLabel, segmentName, handle);
    syncProgress(state, handle, pending);

    var producerAlive = transcodeExecutor.isRunning(sessionId, variantLabel);
    if (producerAlive && !hasStalled(state)) {
      return null;
    }

    return recover(state, pending, replacementReason(producerAlive, positioned, session));
  }

  private static ReplacementReason replacementReason(
      boolean producerAlive, boolean positioned, StreamSession session) {
    if (producerAlive) {
      return ReplacementReason.STALLED;
    }
    if (!positioned && session.isSuspended()) {
      return ReplacementReason.RESUME_FAILED;
    }
    return ReplacementReason.DEAD;
  }

  private static PendingSegment pendingSegment(
      UUID sessionId, String variantLabel, String segmentName, TranscodeHandle handle) {
    return new PendingSegment(
        sessionId, variantLabel, segmentName, requestedIndex(segmentName, handle));
  }

  /** Drops all delivery bookkeeping for a destroyed session. */
  public void forgetSession(UUID sessionId) {
    states.keySet().removeIf(key -> key.sessionId().equals(sessionId));
  }

  private SegmentDelivery tryRead(UUID sessionId, String segmentName) {
    if (!segmentStore.segmentExists(sessionId, segmentName)) {
      return null;
    }

    try {
      return new SegmentDelivery.Ready(segmentStore.readSegment(sessionId, segmentName));
    } catch (TranscodeException _) {
      // A concurrent destroy can win between the existence check and the read; the next
      // iteration classifies the session state instead of surfacing a server error.
      return null;
    }
  }

  /**
   * {@code init.mp4} carries no index of its own; every run rewrites it, so it maps to the current
   * run's start sequence number.
   */
  private static int requestedIndex(String segmentName, TranscodeHandle handle) {
    return SegmentNames.indexOf(segmentName).orElse(handle.startSequenceNumber());
  }

  /**
   * Positioning follows media-segment indices; an init request must never relocate a mid-timeline
   * producer to index 0. A suspended session still resumes so a lone init request cannot wait on a
   * producer nothing will start.
   *
   * <p>Returns false when positioning could not start a producer (e.g. a resume with no eligible
   * worker): the failure is folded into recovery classification instead of escaping as a raw server
   * error.
   */
  private boolean tryEnsurePositioned(StreamSession session, String segmentName) {
    if (SegmentNames.indexOf(segmentName).isEmpty() && !session.isSuspended()) {
      return true;
    }

    try {
      producerLifecycle.ensurePositioned(session.getSessionId(), segmentName);
      return true;
    } catch (TranscodeException e) {
      log.warn(
          "Positioning failed for session {} segment {}: {}",
          session.getSessionId(),
          segmentName,
          e.getMessage());
      return false;
    }
  }

  private void syncProgress(
      VariantDeliveryState state, TranscodeHandle handle, PendingSegment pending) {
    state.syncProgress(
        handle,
        pending,
        index ->
            segmentStore.segmentExists(
                pending.sessionId(), SegmentNames.siblingName(pending.segmentName(), index)),
        clock.instant());
  }

  private boolean hasStalled(VariantDeliveryState state) {
    return state.hasStalled(properties.producerStallThreshold(), clock.instant());
  }

  private boolean sleepOnePoll() {
    try {
      Thread.sleep(pollInterval.toMillis());
      return true;
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * The producer is dead or stalled: open a recovery cycle if none is active — snapshotting the
   * eligible targets once — and try the first unattempted target. Returns null to resume polling,
   * or a terminal outcome.
   */
  private SegmentDelivery recover(
      VariantDeliveryState state, PendingSegment pending, ReplacementReason reason) {
    var opened = state.openOrCurrentCycle(transcodeExecutor.executionTargets());
    logProducerEnd(pending, opened.currentAttemptId(), reason);

    return attemptReplacements(state, opened.cycle(), pending, reason);
  }

  private SegmentDelivery attemptReplacements(
      VariantDeliveryState state,
      RecoveryCycle cycle,
      PendingSegment pending,
      ReplacementReason reason) {
    while (true) {
      // A producer that is alive and within its stall budget — typically another waiter's fresh
      // replacement — means recovery is not (or no longer) needed; never dispatch or exhaust
      // underneath it.
      if (transcodeExecutor.isRunning(pending.sessionId(), pending.variantLabel())
          && !hasStalled(state)) {
        return null;
      }

      var ticket = state.nextTicket(cycle);
      var expectedAttemptId = ticket.expectedAttemptId();
      var target = ticket.target();
      if (target == null) {
        return exhaust(state, cycle, pending, expectedAttemptId);
      }

      var result =
          producerLifecycle.replaceProducer(
              ReplaceProducerCommand.builder()
                  .sessionId(pending.sessionId())
                  .variantLabel(pending.variantLabel())
                  .segmentName(pending.segmentName())
                  .segmentIndex(pending.requestedIndex())
                  .expectedAttemptId(expectedAttemptId)
                  .reason(reason)
                  .target(target)
                  .build());

      switch (result) {
        case ReplaceResult.Replaced(UUID newAttemptId) -> {
          state.recordReplacement(
              cycle, target, newAttemptId, pending.requestedIndex(), clock.instant());
          log.info(
              "Replaced producer for session {} variant {} on target {} at segment {} (attempt {})",
              pending.sessionId(),
              pending.variantLabel(),
              target.value(),
              pending.requestedIndex(),
              newAttemptId);
          return null;
        }
        case ReplaceResult.Refused(String refusal) -> {
          state.recordRefusal(cycle, target);
          log.warn(
              "Execution target {} refused replacement for session {} variant {}: {}",
              target.value(),
              pending.sessionId(),
              pending.variantLabel(),
              refusal);
        }
        case ReplaceResult.Superseded() -> {
          return null;
        }
        case ReplaceResult.SessionGone() -> {
          return null;
        }
      }
    }
  }

  private SegmentDelivery exhaust(
      VariantDeliveryState state,
      RecoveryCycle cycle,
      PendingSegment pending,
      UUID expectedAttemptId) {
    var result =
        producerLifecycle.markExhausted(
            pending.sessionId(), pending.variantLabel(), expectedAttemptId);
    switch (result) {
      case ExhaustResult.Superseded() -> {
        return null;
      }
      case ExhaustResult.Exhausted() -> {
        // fall through to the terminal checks below
      }
    }

    if (segmentStore.segmentExists(pending.sessionId(), pending.segmentName())) {
      // A last-gasp publication raced the exhaustion; serve it on the next iteration.
      return null;
    }

    state.retainExhaustedCycle(cycle, expectedAttemptId);
    log.warn(
        "Recovery exhausted for session {} variant {}: every execution target in {} was tried",
        pending.sessionId(),
        pending.variantLabel(),
        cycle.eligibleTargets().stream().map(ExecutionTargetId::value).toList());
    return new SegmentDelivery.Unrecoverable();
  }

  /**
   * A {@code FAILED} variant is terminal for same-window retries. Two things revive it: a genuine
   * seek (relocation distance — a planned restart per ADR 0019's seek clause), or a currently
   * eligible target outside the exhausted cycle's snapshot (the new-target reset). Returns null to
   * continue the delivery loop, or the terminal 503 outcome.
   */
  private SegmentDelivery resolveFailedVariant(
      VariantDeliveryState state,
      StreamSession session,
      TranscodeHandle handle,
      PendingSegment pending) {
    tryEnsurePositioned(session, pending.segmentName());
    var refreshed = session.getVariantHandle(pending.variantLabel());
    if (refreshed == null) {
      return new SegmentDelivery.SessionEnded();
    }
    if (!refreshed.attemptId().equals(handle.attemptId())
        || refreshed.status() != TranscodeStatus.FAILED) {
      // The planned seek restart (or another actor) revived the variant; re-observe.
      return null;
    }

    var reset =
        state.resetForFreshTargets(
            transcodeExecutor.executionTargets(), refreshed, clock.instant());
    if (reset.isEmpty()) {
      return new SegmentDelivery.Unrecoverable();
    }

    log.info(
        "New execution target(s) {} reset recovery for session {} variant {}",
        reset.get().freshTargets().stream().map(ExecutionTargetId::value).toList(),
        pending.sessionId(),
        pending.variantLabel());
    return attemptReplacements(
        state,
        reset.get().cycle(),
        pendingSegment(
            pending.sessionId(), pending.variantLabel(), pending.segmentName(), refreshed),
        ReplacementReason.DEAD);
  }

  private void logProducerEnd(PendingSegment pending, UUID attemptId, ReplacementReason reason) {
    if (reason == ReplacementReason.STALLED) {
      log.warn(
          "Producer stalled for session {} variant {} (attempt {}): no publication within {}",
          pending.sessionId(),
          pending.variantLabel(),
          attemptId,
          properties.producerStallThreshold());
      return;
    }

    if (reason == ReplacementReason.RESUME_FAILED) {
      log.warn(
          "Resume could not start a producer for session {} variant {} (attempt {}); recovering"
              + " across execution targets",
          pending.sessionId(),
          pending.variantLabel(),
          attemptId);
      return;
    }

    // The death site (process manager or worker registry) has already logged its own detail.
    log.warn(
        "Producer died for session {} variant {} (attempt {}); recovering across execution"
            + " targets",
        pending.sessionId(),
        pending.variantLabel(),
        attemptId);
  }

  /** One advertised segment being pursued: where it belongs and the index it maps to. */
  private record PendingSegment(
      UUID sessionId, String variantLabel, String segmentName, int requestedIndex) {}

  private record VariantKey(UUID sessionId, String variantLabel) {}

  private record OpenedCycle(RecoveryCycle cycle, UUID currentAttemptId) {}

  private record ReplacementTicket(UUID expectedAttemptId, ExecutionTargetId target) {}

  private record CycleReset(RecoveryCycle cycle, Set<ExecutionTargetId> freshTargets) {}

  /**
   * Bookkeeping for one variant's deliveries. The instance owns its monitor: every read or write of
   * the tracked attempt, frontier, stall clock, or recovery cycle goes through a synchronized
   * method here.
   */
  private static final class VariantDeliveryState {

    private UUID trackedAttemptId;
    private int frontier;
    private Instant lastProgressAt = Instant.EPOCH;
    private RecoveryCycle cycle;

    /**
     * Tracks the producer run the requests are waiting on. A new attempt installed by this cycle's
     * own replacement keeps the cycle open (so a target is never retried within one cycle); any
     * other new attempt is a planned restart and closes it. Advancing the frontier — a segment of
     * the current run newly published — is the one success signal that ends recovery.
     */
    private synchronized void syncProgress(
        TranscodeHandle handle,
        PendingSegment pending,
        IntPredicate frontierSegmentExists,
        Instant now) {
      if (!handle.attemptId().equals(trackedAttemptId)) {
        var ownReplacement = cycle != null && handle.attemptId().equals(cycle.currentAttemptId());
        if (!ownReplacement) {
          cycle = null;
        }
        trackedAttemptId = handle.attemptId();
        frontier = handle.startSequenceNumber();
        lastProgressAt = now;
      }

      var advanced = false;
      while (frontier < pending.requestedIndex() && frontierSegmentExists.test(frontier)) {
        frontier++;
        advanced = true;
      }
      if (advanced) {
        lastProgressAt = now;
        cycle = null;
      }
    }

    private synchronized boolean hasStalled(Duration stallThreshold, Instant now) {
      return Duration.between(lastProgressAt, now).compareTo(stallThreshold) >= 0;
    }

    private synchronized OpenedCycle openOrCurrentCycle(Set<ExecutionTargetId> eligibleTargets) {
      if (cycle == null) {
        cycle = new RecoveryCycle(eligibleTargets, trackedAttemptId);
      }
      return new OpenedCycle(cycle, cycle.currentAttemptId());
    }

    private synchronized ReplacementTicket nextTicket(RecoveryCycle activeCycle) {
      return new ReplacementTicket(activeCycle.currentAttemptId(), activeCycle.firstUnattempted());
    }

    private synchronized void recordReplacement(
        RecoveryCycle activeCycle,
        ExecutionTargetId target,
        UUID newAttemptId,
        int requestedIndex,
        Instant now) {
      activeCycle.markAttempted(target);
      activeCycle.trackReplacement(newAttemptId);
      trackedAttemptId = newAttemptId;
      frontier = requestedIndex;
      lastProgressAt = now;
    }

    private synchronized void recordRefusal(RecoveryCycle activeCycle, ExecutionTargetId target) {
      activeCycle.markAttempted(target);
    }

    /**
     * The exhausted cycle is retained as the baseline for the new-target reset — but only while its
     * attempt is still the tracked one. A planned restart (e.g. a seek revival) that raced in owns
     * the state now; resurrecting the stale cycle over it would wedge the variant behind an
     * exhausted snapshot no death ever opened.
     */
    private synchronized void retainExhaustedCycle(
        RecoveryCycle exhaustedCycle, UUID expectedAttemptId) {
      if (cycle == null && expectedAttemptId.equals(trackedAttemptId)) {
        cycle = exhaustedCycle;
      }
    }

    /**
     * Opens a reset cycle when the eligible targets contain an identity outside the exhausted
     * cycle's snapshot. With no memory of the exhausted cycle the variant stays terminal (fail
     * closed): a missing snapshot must not read as "everything is a new target".
     */
    private synchronized Optional<CycleReset> resetForFreshTargets(
        Set<ExecutionTargetId> eligibleNow, TranscodeHandle refreshed, Instant now) {
      if (cycle == null) {
        return Optional.empty();
      }

      var freshTargets = new LinkedHashSet<>(eligibleNow);
      freshTargets.removeAll(cycle.eligibleTargets());
      if (freshTargets.isEmpty()) {
        return Optional.empty();
      }

      cycle = new RecoveryCycle(eligibleNow, refreshed.attemptId());
      trackedAttemptId = refreshed.attemptId();
      frontier = refreshed.startSequenceNumber();
      lastProgressAt = now;
      return Optional.of(new CycleReset(cycle, Set.copyOf(freshTargets)));
    }
  }

  /**
   * One bounded pass over the execution targets that were eligible when the producer died. The
   * snapshot is fixed at cycle start — a target connecting mid-cycle joins the next cycle via the
   * new-target reset — and the cycle survives its own replacements so a target is attempted at most
   * once per cycle.
   */
  private static final class RecoveryCycle {

    private final Set<ExecutionTargetId> eligibleTargets;
    private final Set<ExecutionTargetId> attempted = new LinkedHashSet<>();
    private UUID currentAttemptId;

    private RecoveryCycle(Set<ExecutionTargetId> eligibleTargets, UUID supersededAttemptId) {
      this.eligibleTargets = new LinkedHashSet<>(eligibleTargets);
      currentAttemptId = supersededAttemptId;
    }

    private ExecutionTargetId firstUnattempted() {
      return eligibleTargets.stream()
          .filter(target -> !attempted.contains(target))
          .findFirst()
          .orElse(null);
    }

    private void markAttempted(ExecutionTargetId target) {
      attempted.add(target);
    }

    private void trackReplacement(UUID newAttemptId) {
      currentAttemptId = newAttemptId;
    }

    private UUID currentAttemptId() {
      return currentAttemptId;
    }

    private Set<ExecutionTargetId> eligibleTargets() {
      return eligibleTargets;
    }
  }
}
