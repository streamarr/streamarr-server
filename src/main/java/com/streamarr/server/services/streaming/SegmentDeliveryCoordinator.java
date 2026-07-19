package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.ProducerEnd;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
        var pending = pendingSegment(sessionId, variantLabel, segmentName, handle);
        var outcome = resolveFailedVariant(state, session, handle, pending);
        if (outcome != null) {
          return outcome;
        }
        if (!sleepOnePoll()) {
          return new SegmentDelivery.Cancelled();
        }
        continue;
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
        if (!sleepOnePoll()) {
          return new SegmentDelivery.Cancelled();
        }
        continue;
      }

      var outcome = recover(state, pending, replacementReason(producerAlive, positioned, session));
      if (outcome != null) {
        return outcome;
      }
      // A pass that neither replaced nor terminated (superseded, or another waiter's healthy
      // replacement) must re-observe at poll cadence, never in a hot loop.
      if (!sleepOnePoll()) {
        return new SegmentDelivery.Cancelled();
      }
    }
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

  /**
   * Tracks the producer run the requests are waiting on. A new attempt installed by this cycle's
   * own replacement keeps the cycle open (so a target is never retried within one cycle); any other
   * new attempt is a planned restart and closes it. Advancing the frontier — a segment of the
   * current run newly published — is the one success signal that ends recovery.
   */
  private void syncProgress(
      VariantDeliveryState state, TranscodeHandle handle, PendingSegment pending) {
    synchronized (state) {
      if (!handle.attemptId().equals(state.trackedAttemptId)) {
        var ownReplacement =
            state.cycle != null && handle.attemptId().equals(state.cycle.currentAttemptId());
        if (!ownReplacement) {
          state.cycle = null;
        }
        state.trackedAttemptId = handle.attemptId();
        state.frontier = handle.startSequenceNumber();
        state.lastProgressAt = clock.instant();
      }

      var advanced = false;
      while (state.frontier < pending.requestedIndex()
          && segmentStore.segmentExists(
              pending.sessionId(),
              SegmentNames.siblingName(pending.segmentName(), state.frontier))) {
        state.frontier++;
        advanced = true;
      }
      if (advanced) {
        state.lastProgressAt = clock.instant();
        state.cycle = null;
      }
    }
  }

  private boolean hasStalled(VariantDeliveryState state) {
    synchronized (state) {
      return Duration.between(state.lastProgressAt, clock.instant())
              .compareTo(properties.producerStallThreshold())
          >= 0;
    }
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
    RecoveryCycle cycle;
    UUID endedAttemptId;
    synchronized (state) {
      if (state.cycle == null) {
        state.cycle =
            new RecoveryCycle(transcodeExecutor.executionTargets(), state.trackedAttemptId);
      }
      cycle = state.cycle;
      endedAttemptId = cycle.currentAttemptId();
    }
    logProducerEnd(pending, endedAttemptId, reason);

    return attemptReplacements(state, cycle, pending, reason);
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

      UUID expectedAttemptId;
      ExecutionTargetId target;
      synchronized (state) {
        expectedAttemptId = cycle.currentAttemptId();
        target = cycle.firstUnattempted();
      }
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
          synchronized (state) {
            cycle.markAttempted(target);
            cycle.trackReplacement(newAttemptId);
            state.trackedAttemptId = newAttemptId;
            state.frontier = pending.requestedIndex();
            state.lastProgressAt = clock.instant();
          }
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
          synchronized (state) {
            cycle.markAttempted(target);
          }
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

    synchronized (state) {
      // The exhausted cycle is retained as the baseline for the new-target reset — but only while
      // this cycle's attempt is still the tracked one. A planned restart (e.g. a seek revival)
      // that raced in owns the state now; resurrecting the stale cycle over it would wedge the
      // variant behind an exhausted snapshot no death ever opened.
      if (state.cycle == null && expectedAttemptId.equals(state.trackedAttemptId)) {
        state.cycle = cycle;
      }
    }
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

    RecoveryCycle resetCycle;
    synchronized (state) {
      var eligibleNow = transcodeExecutor.executionTargets();
      var freshTargets = targetsOutsideExhaustedSnapshot(state.cycle, eligibleNow);
      if (freshTargets.isEmpty()) {
        return new SegmentDelivery.Unrecoverable();
      }

      log.info(
          "New execution target(s) {} reset recovery for session {} variant {}",
          freshTargets.stream().map(ExecutionTargetId::value).toList(),
          pending.sessionId(),
          pending.variantLabel());
      resetCycle = new RecoveryCycle(eligibleNow, refreshed.attemptId());
      state.cycle = resetCycle;
      state.trackedAttemptId = refreshed.attemptId();
      state.frontier = refreshed.startSequenceNumber();
      state.lastProgressAt = clock.instant();
    }

    return attemptReplacements(
        state,
        resetCycle,
        pendingSegment(
            pending.sessionId(), pending.variantLabel(), pending.segmentName(), refreshed),
        ReplacementReason.DEAD);
  }

  /**
   * With no memory of the exhausted cycle the variant stays terminal (fail closed): a missing
   * snapshot must not read as "everything is a new target".
   */
  private static Set<ExecutionTargetId> targetsOutsideExhaustedSnapshot(
      RecoveryCycle exhaustedCycle, Set<ExecutionTargetId> eligibleNow) {
    if (exhaustedCycle == null) {
      return Set.of();
    }

    var fresh = new LinkedHashSet<>(eligibleNow);
    fresh.removeAll(exhaustedCycle.eligibleTargets());
    return fresh;
  }

  private void logProducerEnd(PendingSegment pending, UUID attemptId, ReplacementReason reason) {
    if (reason == ReplacementReason.STALLED) {
      log.warn(
          "Producer classified {} for session {} variant {} (attempt {}): no publication within {}",
          ProducerEnd.EndKind.STALLED,
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

    transcodeExecutor
        .deathEvidence(pending.sessionId(), pending.variantLabel(), attemptId)
        .ifPresentOrElse(
            end ->
                log.warn(
                    "Producer ended for session {} variant {} (attempt {}): {} — {}",
                    pending.sessionId(),
                    pending.variantLabel(),
                    attemptId,
                    end.kind(),
                    end.detail()),
            () ->
                log.warn(
                    "Producer died without retained evidence for session {} variant {} (attempt {})",
                    pending.sessionId(),
                    pending.variantLabel(),
                    attemptId));
  }

  /** One advertised segment being pursued: where it belongs and the index it maps to. */
  private record PendingSegment(
      UUID sessionId, String variantLabel, String segmentName, int requestedIndex) {}

  private record VariantKey(UUID sessionId, String variantLabel) {}

  /** Bookkeeping for one variant's deliveries; all access synchronizes on the instance. */
  private static final class VariantDeliveryState {
    private UUID trackedAttemptId;
    private int frontier;
    private Instant lastProgressAt = Instant.EPOCH;
    private RecoveryCycle cycle;
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
