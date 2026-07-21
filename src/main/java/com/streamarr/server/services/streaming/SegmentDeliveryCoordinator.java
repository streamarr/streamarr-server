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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the segment wait loop and producer recovery (ADR 0019, issue #252). A request for an
 * advertised-but-missing segment waits on producer liveness and publication progress — never a wall
 * clock — and a dead or stalled producer is replaced at the requested segment's offset, trying each
 * currently eligible execution target at most once since the last publication progress. Only when
 * no eligible target remains untried does the variant become terminally {@code FAILED}; a target
 * never attempted in the failed window, or a genuine seek, revives it.
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
    if (matchesNoNamingScheme(segmentName)) {
      // Waiting on a name no run can produce would misread the frontier and stall-kill a healthy
      // producer; an unknown name is a 404, never a recovery trigger.
      log.debug(
          "Rejected segment request matching no naming scheme: session {} name {}",
          sessionId,
          segmentName);
      return new SegmentDelivery.SessionEnded();
    }

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

  private static boolean matchesNoNamingScheme(String segmentName) {
    return SegmentNames.indexOf(segmentName).isEmpty() && !SegmentNames.isInitSegment(segmentName);
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
    } catch (TranscodeException e) {
      // A concurrent destroy can win between the existence check and the read; the next
      // iteration classifies the session state instead of surfacing a server error. Logged so a
      // store failing for any other reason stays observable in this poll loop.
      log.debug(
          "Segment read raced a concurrent destroy: session {} name {}", sessionId, segmentName, e);
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
   * The producer is dead or stalled: try the first live execution target not yet attempted since
   * the last publication progress. Returns null to resume polling, or a terminal outcome.
   */
  private SegmentDelivery recover(
      VariantDeliveryState state, PendingSegment pending, ReplacementReason reason) {
    logProducerEnd(pending, state.trackedAttempt(), reason);
    return attemptReplacements(state, pending, reason);
  }

  private SegmentDelivery attemptReplacements(
      VariantDeliveryState state, PendingSegment pending, ReplacementReason reason) {
    while (true) {
      // A producer that is alive and within its stall budget — typically another waiter's fresh
      // replacement — means recovery is not (or no longer) needed; never dispatch or exhaust
      // underneath it.
      if (transcodeExecutor.isRunning(pending.sessionId(), pending.variantLabel())
          && !hasStalled(state)) {
        return null;
      }

      var ticket = state.nextTicket(transcodeExecutor.executionTargets());
      var expectedAttemptId = ticket.expectedAttemptId();
      var target = ticket.target();
      if (target == null) {
        return exhaust(state, pending, expectedAttemptId);
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
          state.recordReplacement(target, newAttemptId, pending.requestedIndex(), clock.instant());
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
          state.recordRefusal(target);
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
      VariantDeliveryState state, PendingSegment pending, UUID expectedAttemptId) {
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

    log.warn(
        "Recovery exhausted for session {} variant {}: every eligible execution target in {} was"
            + " tried",
        pending.sessionId(),
        pending.variantLabel(),
        state.attemptedTargets().stream().map(ExecutionTargetId::value).toList());
    return new SegmentDelivery.Unrecoverable();
  }

  /**
   * A {@code FAILED} variant is terminal for same-window retries. Two things revive it: a genuine
   * seek (relocation distance — a planned restart per ADR 0019's seek clause), or a currently
   * eligible target never attempted in the failed window (a reconnecting worker is a new target by
   * construction). Returns null to continue the delivery loop, or the terminal 503 outcome.
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
        reset.get().stream().map(ExecutionTargetId::value).toList(),
        pending.sessionId(),
        pending.variantLabel());
    return attemptReplacements(
        state,
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

  private record ReplacementTicket(UUID expectedAttemptId, ExecutionTargetId target) {}

  /**
   * Bookkeeping for one variant's deliveries. The instance owns its monitor: every read or write of
   * the tracked attempt, frontier, stall clock, or attempted-target log goes through a synchronized
   * method here.
   *
   * <p>{@code attemptedSinceProgress} is a log of this coordinator's own replacement actions since
   * the last publication progress — never a snapshot of fleet membership. Every pass compares it
   * against the live target set, so a target appearing mid-recovery is simply tried and a departed
   * one stops mattering.
   */
  private static final class VariantDeliveryState {

    private UUID trackedAttemptId;
    private int frontier;
    private Instant lastProgressAt = Instant.EPOCH;
    private final Set<ExecutionTargetId> attemptedSinceProgress = new LinkedHashSet<>();

    /**
     * Tracks the producer run the requests are waiting on. An attempt this state did not itself
     * record is a planned restart and clears the attempted log; advancing the frontier — a segment
     * of the current run newly published — is the one success signal that ends recovery.
     */
    private synchronized void syncProgress(
        TranscodeHandle handle,
        PendingSegment pending,
        IntPredicate frontierSegmentExists,
        Instant now) {
      if (!handle.attemptId().equals(trackedAttemptId)) {
        attemptedSinceProgress.clear();
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
        attemptedSinceProgress.clear();
      }
    }

    private synchronized boolean hasStalled(Duration stallThreshold, Instant now) {
      return Duration.between(lastProgressAt, now).compareTo(stallThreshold) >= 0;
    }

    private synchronized UUID trackedAttempt() {
      return trackedAttemptId;
    }

    private synchronized List<ExecutionTargetId> attemptedTargets() {
      return List.copyOf(attemptedSinceProgress);
    }

    /** The first live target not yet attempted since the last progress; null means exhausted. */
    private synchronized ReplacementTicket nextTicket(Set<ExecutionTargetId> liveTargets) {
      var target =
          liveTargets.stream()
              .filter(candidate -> !attemptedSinceProgress.contains(candidate))
              .findFirst()
              .orElse(null);
      return new ReplacementTicket(trackedAttemptId, target);
    }

    private synchronized void recordReplacement(
        ExecutionTargetId target, UUID newAttemptId, int requestedIndex, Instant now) {
      attemptedSinceProgress.add(target);
      trackedAttemptId = newAttemptId;
      frontier = requestedIndex;
      lastProgressAt = now;
    }

    private synchronized void recordRefusal(ExecutionTargetId target) {
      attemptedSinceProgress.add(target);
    }

    /**
     * Revives a {@code FAILED} variant when a currently eligible target was never attempted in the
     * failed window; with no such target the variant stays terminal for same-window retries. A
     * revival reopens recovery across every currently eligible target.
     */
    private synchronized Optional<Set<ExecutionTargetId>> resetForFreshTargets(
        Set<ExecutionTargetId> eligibleNow, TranscodeHandle refreshed, Instant now) {
      var freshTargets = new LinkedHashSet<>(eligibleNow);
      freshTargets.removeAll(attemptedSinceProgress);
      if (freshTargets.isEmpty()) {
        return Optional.empty();
      }

      attemptedSinceProgress.clear();
      trackedAttemptId = refreshed.attemptId();
      frontier = refreshed.startSequenceNumber();
      lastProgressAt = now;
      return Optional.of(Set.copyOf(freshTargets));
    }
  }
}
