package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.streaming.ProducerLifecycleService.ReplaceProducerCommand;
import com.streamarr.server.services.streaming.ProducerLifecycleService.ReplaceResult;
import com.streamarr.server.services.streaming.ProducerLifecycleService.ReplacementReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
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
 * clock — and a dead or stalled producer is replaced at the requested segment's offset. Every pass
 * derives the live execution targets and tries the first target absent from the attempted-target
 * log. Publication progress clears that log; changing fleet membership does not. Only when no live
 * target remains untried does the variant become terminally {@code FAILED}; a target never
 * attempted in the failed window, or a genuine seek, revives it.
 *
 * <p>Coordinator state is the per-variant {@code states} map, each entry guarded by its own
 * monitor. Producer mutation is serialized by {@link ProducerLifecycleService}'s per-session mutex,
 * whose atomic predicate guarantees at most one producer start per death regardless of concurrent
 * waiters.
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

    var handle = session.getVariantHandle(variantLabel).orElse(null);
    if (handle == null) {
      return new SegmentDelivery.SessionEnded();
    }

    // A variant's delivery state is created only for validated requests, so post-destroy retries
    // cannot re-grow entries that forgetSession already dropped.
    var variantKey = new VariantKey(sessionId, variantLabel);
    var state = states.computeIfAbsent(variantKey, _ -> new VariantDeliveryState());
    if (runtimeRegistry.findById(sessionId).isEmpty()) {
      states.remove(variantKey, state);
      return new SegmentDelivery.SessionEnded();
    }

    if (handle.status() == TranscodeStatus.FAILED) {
      return resolveFailedVariant(
          state, session, handle, pendingSegment(sessionId, variantLabel, segmentName, handle));
    }

    var positioned = tryEnsurePositioned(session, segmentName);
    // Re-read because positioning may have replaced the handle; it cannot have vanished — handles
    // are only ever added, and a destroyed session is caught by the registry lookup instead.
    handle = session.getVariantHandle(variantLabel).orElseThrow();

    var pending = pendingSegment(sessionId, variantLabel, segmentName, handle);
    syncProgress(state, handle, pending);

    var producerAlive = transcodeExecutor.isRunning(sessionId, variantLabel);
    if (producerAlive && !hasStalled(state)) {
      return null;
    }

    var reason = replacementReason(producerAlive, positioned, session);
    logProducerEnd(pending, state.trackedAttempt(), reason);
    return attemptReplacements(state, pending, reason);
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

  /** Drops every per-variant delivery state for a destroyed session. */
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
      // A concurrent destroy can remove the segment between the existence check and the read. The
      // store reports that disappearance as TranscodeException, so re-observe the session state.
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
        index ->
            segmentStore.segmentExists(
                pending.sessionId(), SegmentNames.siblingName(pending.segmentName(), index)),
        clock.instant());
  }

  /**
   * A run that has not yet published its first segment is still encoding one, so it gets the
   * steady-state threshold plus a whole segment of encoding time. Without that, a merely slow cold
   * start is read as a hang and replaced by a byte-identical run that is no faster.
   */
  private boolean hasStalled(VariantDeliveryState state) {
    return state.hasStalled(
        properties.producerStallThreshold(),
        properties.producerStallThreshold().plus(properties.targetSegmentDuration()),
        clock.instant());
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

  /** Tries live execution targets not attempted since the last publication progress. */
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
          if (!state.recordReplacement(
              target, expectedAttemptId, newAttemptId, pending.requestedIndex(), clock.instant())) {
            return null;
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
          state.recordRefusal(target, expectedAttemptId);
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
    if (!producerLifecycle.tryMarkExhausted(
        pending.sessionId(), pending.variantLabel(), expectedAttemptId)) {
      return null;
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
   * construction). Returns null to continue the delivery loop, or the terminal 503 outcome when no
   * revival is possible.
   */
  private SegmentDelivery resolveFailedVariant(
      VariantDeliveryState state,
      StreamSession session,
      TranscodeHandle handle,
      PendingSegment pending) {
    tryEnsurePositioned(session, pending.segmentName());
    var refreshed = session.getVariantHandle(pending.variantLabel()).orElseThrow();
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
    var message =
        switch (reason) {
          case STALLED ->
              "Producer stalled for session {} variant {} (attempt {}): no publication within "
                  + properties.producerStallThreshold()
                  + "; recovering across execution targets";
          case RESUME_FAILED ->
              "Resume could not start a producer for session {} variant {} (attempt {});"
                  + " recovering across execution targets";
          // The death site (process manager or worker registry) has already logged its detail.
          case DEAD ->
              "Producer died for session {} variant {} (attempt {}); recovering across execution"
                  + " targets";
        };
    log.warn(message, pending.sessionId(), pending.variantLabel(), attemptId);
  }

  /** One advertised segment being pursued: where it belongs and the index it maps to. */
  private record PendingSegment(
      UUID sessionId, String variantLabel, String segmentName, int requestedIndex) {}

  private record VariantKey(UUID sessionId, String variantLabel) {}

  private record ReplacementTicket(UUID expectedAttemptId, ExecutionTargetId target) {}

  /**
   * State for one variant's deliveries. The instance owns its monitor: every read or write of the
   * tracked attempt, frontier, stall clock, or attempted-target log goes through a synchronized
   * method here.
   *
   * <p>{@code attemptedSinceProgress} is a log of this coordinator's own replacement actions since
   * the last publication progress — never a snapshot of fleet membership. Every pass compares it
   * against the live target set, so a target appearing mid-recovery is simply tried and a departed
   * one stops mattering.
   */
  private static final class VariantDeliveryState {

    private UUID trackedAttemptId;
    private int runStart;
    private int frontier;
    private Instant lastProgressAt = Instant.EPOCH;
    private final Set<ExecutionTargetId> attemptedSinceProgress = new LinkedHashSet<>();

    /**
     * Tracks the producer run the requests are waiting on. An attempt this state did not itself
     * record is a planned restart and clears the attempted log; advancing the frontier — a segment
     * of the current run newly published — is the one success signal that ends recovery.
     *
     * <p>The frontier follows the producer's own output rather than stopping at the requested
     * index. A request for the run's first segment — every cold start, every seek, and every {@code
     * init.mp4}, which resolves to the run's start — has nothing below it to observe, so bounding
     * the scan there would leave those requests permanently unable to see progress.
     */
    private synchronized void syncProgress(
        TranscodeHandle handle, IntPredicate frontierSegmentExists, Instant now) {
      if (!handle.attemptId().equals(trackedAttemptId)) {
        attemptedSinceProgress.clear();
        trackedAttemptId = handle.attemptId();
        runStart = handle.startSequenceNumber();
        frontier = handle.startSequenceNumber();
        lastProgressAt = now;
      }

      var advanced = false;
      while (frontierSegmentExists.test(frontier)) {
        frontier++;
        advanced = true;
      }
      if (advanced) {
        lastProgressAt = now;
        attemptedSinceProgress.clear();
      }
    }

    /** Whether this run has published at least one segment of its own. */
    private synchronized boolean hasPublished() {
      return frontier > runStart;
    }

    private synchronized boolean hasStalled(
        Duration stallThreshold, Duration startupThreshold, Instant now) {
      var budget = hasPublished() ? stallThreshold : startupThreshold;
      return Duration.between(lastProgressAt, now).compareTo(budget) >= 0;
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

    private synchronized boolean recordReplacement(
        ExecutionTargetId target,
        UUID expectedAttemptId,
        UUID newAttemptId,
        int requestedIndex,
        Instant now) {
      if (!Objects.equals(trackedAttemptId, expectedAttemptId)) {
        return false;
      }
      attemptedSinceProgress.add(target);
      trackedAttemptId = newAttemptId;
      runStart = requestedIndex;
      frontier = requestedIndex;
      lastProgressAt = now;
      return true;
    }

    /**
     * Fenced like every other recovery action: between {@code replaceProducer} returning and this
     * call, a planned restart can install and sync a new attempt, and recording the old refusal
     * against the new window could wrongly exhaust its only eligible target.
     */
    private synchronized void recordRefusal(ExecutionTargetId target, UUID expectedAttemptId) {
      if (!Objects.equals(trackedAttemptId, expectedAttemptId)) {
        return;
      }
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
