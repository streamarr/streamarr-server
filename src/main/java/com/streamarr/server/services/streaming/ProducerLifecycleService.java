package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.concurrency.MutexFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns every producer (transcode process) mutation for a stream session: initial start, resume of a
 * suspended session, relocation to a distant segment, replacement, and suspension. Every mutation
 * serializes on the per-session mutex this service holds, including initial startup because the
 * session is published before its producers finish starting.
 */
@Slf4j
@Builder
public class ProducerLifecycleService {

  /** Beyond this lead, restarting the encoder beats waiting for it to catch up. */
  private static final Duration FORWARD_RELOCATION_GAP = Duration.ofSeconds(24);

  private final TranscodeExecutor transcodeExecutor;
  private final SegmentStore segmentStore;
  private final StreamingProperties properties;
  private final RuntimeStreamSessionRegistry runtimeRegistry;
  private final MutexFactory<UUID> sessionMutex;
  @Builder.Default private final Clock clock = Clock.systemUTC();
  private final ConcurrentHashMap<VariantKey, VariantDeliveryState> recoveryStates =
      new ConcurrentHashMap<>();

  public enum RecoveryResult {
    WAITING,
    EXHAUSTED,
    SESSION_GONE
  }

  private record VariantKey(UUID sessionId, String variantLabel) {}

  @Builder
  private record PendingSegment(
      UUID sessionId, String variantLabel, String segmentName, int requestedIndex) {}

  /**
   * Observes progress and decides whether to wait, replace, or exhaust under the same session mutex
   * as every producer mutation. Recovery bookkeeping is committed before releasing it.
   */
  public RecoveryResult recover(UUID sessionId, String variantLabel, String segmentName) {
    return withSessionLock(sessionId, () -> doRecover(sessionId, variantLabel, segmentName));
  }

  public void forgetRecovery(UUID sessionId) {
    withSessionLock(
        sessionId,
        () -> recoveryStates.keySet().removeIf(key -> key.sessionId().equals(sessionId)));
  }

  private RecoveryResult doRecover(UUID sessionId, String variantLabel, String segmentName) {
    var session = runtimeRegistry.findById(sessionId).orElse(null);
    if (session == null) {
      return RecoveryResult.SESSION_GONE;
    }

    var handle = session.getVariantHandle(variantLabel).orElse(null);
    if (handle == null) {
      return RecoveryResult.SESSION_GONE;
    }

    if (segmentStore.segmentExists(sessionId, segmentName)) {
      return RecoveryResult.WAITING;
    }

    var positioned = tryEnsurePositioned(session, segmentName);
    handle = session.getVariantHandle(variantLabel).orElseThrow();
    var state =
        recoveryStates.computeIfAbsent(
            new VariantKey(sessionId, variantLabel), _ -> new VariantDeliveryState());
    var pending =
        PendingSegment.builder()
            .sessionId(sessionId)
            .variantLabel(variantLabel)
            .segmentName(segmentName)
            .requestedIndex(SegmentNames.indexOf(segmentName).orElse(handle.startSequenceNumber()))
            .build();
    if (handle.status() == TranscodeStatus.FAILED
        && !state.resetForFreshTargets(
            transcodeExecutor.executionTargets(), handle, clock.instant())) {
      return RecoveryResult.EXHAUSTED;
    }

    return attemptRecovery(state, pending, positioned);
  }

  private boolean tryEnsurePositioned(StreamSession session, String segmentName) {
    if (SegmentNames.indexOf(segmentName).isEmpty() && !session.isSuspended()) {
      return true;
    }

    try {
      ensurePositioned(session.getSessionId(), segmentName);
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

  private RecoveryResult attemptRecovery(
      VariantDeliveryState state, PendingSegment pending, boolean positioned) {
    while (true) {
      var session = runtimeRegistry.findById(pending.sessionId()).orElse(null);
      if (session == null) {
        return RecoveryResult.SESSION_GONE;
      }

      var handle = session.getVariantHandle(pending.variantLabel()).orElseThrow();
      syncProgress(state, handle, pending);
      var running = transcodeExecutor.isRunning(pending.sessionId(), pending.variantLabel());
      if (running && !hasStalled(state)) {
        return RecoveryResult.WAITING;
      }

      var reason = replacementReason(running, positioned, session);
      if (!isReplaceableStatus(handle, session, reason)) {
        return RecoveryResult.WAITING;
      }

      var eligibleTargets = transcodeExecutor.executionTargets();
      // Segment publication is external to the session mutex and may advance during discovery.
      syncProgress(state, handle, pending);
      if (noLongerNeedsRecovery(state, pending, running)) {
        return RecoveryResult.WAITING;
      }

      var target = state.nextTarget(eligibleTargets);
      if (target.isEmpty()) {
        return exhaust(state, pending, handle.attemptId());
      }

      var command =
          ReplaceProducerCommand.builder()
              .sessionId(pending.sessionId())
              .variantLabel(pending.variantLabel())
              .segmentName(pending.segmentName())
              .segmentIndex(pending.requestedIndex())
              .expectedAttemptId(handle.attemptId())
              .reason(reason)
              .target(target.get())
              .build();
      var outcome = replaceAndRecord(state, session, command);
      if (outcome.isPresent()) {
        return outcome.get();
      }
    }
  }

  private boolean noLongerNeedsRecovery(
      VariantDeliveryState state, PendingSegment pending, boolean running) {
    return segmentStore.segmentExists(pending.sessionId(), pending.segmentName())
        || (running && !hasStalled(state));
  }

  private RecoveryResult exhaust(
      VariantDeliveryState state, PendingSegment pending, UUID expectedAttemptId) {
    if (!doExhaust(pending.sessionId(), pending.variantLabel(), expectedAttemptId)) {
      return RecoveryResult.WAITING;
    }

    log.warn(
        "Recovery exhausted for session {} variant {}: every eligible execution target in {} was tried",
        pending.sessionId(),
        pending.variantLabel(),
        state.attemptedTargets());
    return RecoveryResult.EXHAUSTED;
  }

  private Optional<RecoveryResult> replaceAndRecord(
      VariantDeliveryState state, StreamSession session, ReplaceProducerCommand command) {
    return switch (doReplace(command)) {
      case ReplaceResult.Replaced(UUID newAttemptId) -> {
        state.recordReplacement(
            command.target(),
            session.getVariantHandle(command.variantLabel()).orElseThrow(),
            clock.instant());
        log.info(
            "Replaced producer for session {} variant {} on target {} at segment {} (attempt {})",
            command.sessionId(),
            command.variantLabel(),
            command.target().value(),
            command.segmentIndex(),
            newAttemptId);
        yield Optional.of(RecoveryResult.WAITING);
      }
      case ReplaceResult.Refused(String refusal) -> {
        state.recordRefusal(command.target());
        log.warn(
            "Execution target {} refused replacement for session {} variant {}: {}",
            command.target().value(),
            command.sessionId(),
            command.variantLabel(),
            refusal);
        yield Optional.empty();
      }
      case ReplaceResult.Superseded() -> Optional.of(RecoveryResult.WAITING);
      case ReplaceResult.SessionGone() -> Optional.of(RecoveryResult.SESSION_GONE);
    };
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

  private boolean hasStalled(VariantDeliveryState state) {
    return state.hasStalled(
        properties.producerStallThreshold(),
        properties.producerStallThreshold().plus(properties.targetSegmentDuration()),
        clock.instant());
  }

  private static ReplacementReason replacementReason(
      boolean running, boolean positioned, StreamSession session) {
    if (running) {
      return ReplacementReason.STALLED;
    }

    if (!positioned && session.isSuspended()) {
      return ReplacementReason.RESUME_FAILED;
    }

    return ReplacementReason.DEAD;
  }

  private enum ReplacementReason {
    DEAD,
    STALLED,
    RESUME_FAILED
  }

  @Builder
  private record ReplaceProducerCommand(
      UUID sessionId,
      String variantLabel,
      String segmentName,
      int segmentIndex,
      UUID expectedAttemptId,
      ReplacementReason reason,
      ExecutionTargetId target) {}

  private sealed interface ReplaceResult {
    record Replaced(UUID newAttemptId) implements ReplaceResult {}

    record Refused(String reason) implements ReplaceResult {}

    record Superseded() implements ReplaceResult {}

    record SessionGone() implements ReplaceResult {}
  }

  public void startAll(StreamSession session, int seekPosition, int startSequenceNumber) {
    withSessionLock(
        session.getSessionId(),
        () -> {
          if (session.getVariants().isEmpty()) {
            startSingleTranscode(session, seekPosition, startSequenceNumber);
            return;
          }

          startVariantTranscodes(session, session.getVariants(), seekPosition, startSequenceNumber);
        });
  }

  public void ensurePositioned(UUID sessionId, String segmentName) {
    var session = runtimeRegistry.findById(sessionId).orElse(null);
    if (session == null) {
      return;
    }

    if (segmentStore.segmentExists(sessionId, segmentName)) {
      return;
    }

    if (session.isSuspended()) {
      resumeWithLock(sessionId, segmentName);
      return;
    }

    if (!requiresRelocation(session, segmentName)) {
      return;
    }

    relocateWithLock(sessionId, segmentName);
  }

  public void suspend(UUID sessionId) {
    withSessionLock(sessionId, () -> doSuspend(sessionId));
  }

  private void doSuspend(UUID sessionId) {
    var session = runtimeRegistry.findById(sessionId).orElse(null);
    if (session == null) {
      return;
    }

    transcodeExecutor.stop(session.getSessionId());
    for (var entry : session.getVariantHandles().entrySet()) {
      var handle = entry.getValue();
      if (handle.status() != TranscodeStatus.ACTIVE) {
        continue;
      }

      session.setVariantHandle(entry.getKey(), handle.withStatus(TranscodeStatus.SUSPENDED));
    }

    runtimeRegistry.save(session);
  }

  /**
   * Removes the session from the registry under the session mutex, so an in-flight replace either
   * completes (and its saved session is removed here) or observes the removal — a destroyed session
   * can never be resurrected by a racing save.
   */
  public boolean removeSession(UUID sessionId) {
    return withSessionLock(sessionId, () -> runtimeRegistry.removeById(sessionId).isPresent());
  }

  /**
   * Stops every producer of a session already removed from the registry. Taking the mutex closes
   * the window where a concurrent replace could start a producer for a destroyed session.
   */
  public void stopForDestroy(UUID sessionId) {
    withSessionLock(sessionId, () -> transcodeExecutor.stop(sessionId));
  }

  private ReplaceResult doReplace(ReplaceProducerCommand command) {
    var session = runtimeRegistry.findById(command.sessionId()).orElse(null);
    if (session == null) {
      return new ReplaceResult.SessionGone();
    }

    if (segmentStore.segmentExists(command.sessionId(), command.segmentName())) {
      return new ReplaceResult.Superseded();
    }

    var handle = session.getVariantHandle(command.variantLabel()).orElse(null);
    if (handle == null
        || !handle.attemptId().equals(command.expectedAttemptId())
        || !isReplaceableStatus(handle, session, command.reason())) {
      return new ReplaceResult.Superseded();
    }

    var producerRunning = transcodeExecutor.isRunning(command.sessionId(), command.variantLabel());
    if (producerRunning && command.reason() != ReplacementReason.STALLED) {
      // Only a stall observation licenses stopping a live producer; a caller claiming death
      // against a producer that is running holds a stale view (e.g. another waiter's healthy
      // replacement) and must re-observe instead.
      return new ReplaceResult.Superseded();
    }

    if (producerRunning) {
      transcodeExecutor.stopVariant(command.sessionId(), command.variantLabel());
    }

    TranscodeHandle replacement;
    try {
      replacement = transcodeExecutor.start(replacementRequest(session, command), command.target());
    } catch (TranscodeException refusal) {
      return new ReplaceResult.Refused(refusal.getMessage());
    }

    session.setVariantHandle(command.variantLabel(), replacement);
    runtimeRegistry.save(session);
    return new ReplaceResult.Replaced(replacement.attemptId());
  }

  private boolean doExhaust(UUID sessionId, String variantLabel, UUID expectedAttemptId) {
    var session = runtimeRegistry.findById(sessionId).orElse(null);
    if (session == null) {
      return false;
    }

    // SUSPENDED is exhaustible here: with a healthy suspension every replacement supersedes
    // instead of consuming a target, so exhaustion is only ever reached when nothing — not even a
    // resume — can produce the segment (e.g. no eligible targets after a failed resume).
    var handle = session.getVariantHandle(variantLabel).orElse(null);
    if (handle == null || !handle.attemptId().equals(expectedAttemptId)) {
      return false;
    }

    // The last attempt can be alive but stalled; FAILED promises "no producer", so honor it.
    if (transcodeExecutor.isRunning(sessionId, variantLabel)) {
      transcodeExecutor.stopVariant(sessionId, variantLabel);
    }

    session.setVariantHandle(variantLabel, handle.withStatus(TranscodeStatus.FAILED));
    runtimeRegistry.save(session);
    return true;
  }

  /**
   * A suspended handle is the planned-suspension fence: replaceable only when the caller's resume
   * attempt just failed (recovery is then the only path back to a producer) or when the session is
   * merely part-suspended (a partial resume failure). FAILED stays replaceable so the new-target
   * reset can revive the exhausted attempt.
   */
  private static boolean isReplaceableStatus(
      TranscodeHandle handle, StreamSession session, ReplacementReason reason) {
    return switch (handle.status()) {
      case ACTIVE, FAILED -> true;
      case SUSPENDED -> reason == ReplacementReason.RESUME_FAILED || !session.isSuspended();
      case STARTING, SEEKING, STOPPED -> false;
    };
  }

  private TranscodeRequest replacementRequest(
      StreamSession session, ReplaceProducerCommand command) {
    var request =
        baseRequest(
                session, command.segmentIndex() * segmentDurationSeconds(), command.segmentIndex())
            .variantLabel(command.variantLabel());

    var variant =
        session.getVariants().stream()
            .filter(candidate -> candidate.label().equals(command.variantLabel()))
            .findFirst();
    if (variant.isPresent()) {
      request.width(variant.get().width());
      request.height(variant.get().height());
      request.bitrate(variant.get().videoBitrate());
      return request.build();
    }

    var probe = session.getMediaProbe();
    request.width(probe.width());
    request.height(probe.height());
    request.bitrate(probe.bitrate());
    return request.build();
  }

  private void resumeWithLock(UUID sessionId, String segmentName) {
    withSessionLock(sessionId, () -> doResume(sessionId, segmentName));
  }

  /**
   * A running encoder produces segments sequentially from its start segment. A requested segment
   * needs the encoder relocated when it lies behind that start (it will never be produced) or so
   * far ahead of produced output that waiting would stall the player longer than restarting.
   */
  private boolean requiresRelocation(StreamSession session, String segmentName) {
    var requestedIndex = requestedIndex(session, segmentName);
    var startSequenceNumber = activeStartSequenceNumber(session);
    if (requestedIndex < startSequenceNumber) {
      return true;
    }

    var probeIndex = requestedIndex - forwardGapSegments();
    if (probeIndex < startSequenceNumber) {
      return false;
    }

    return !segmentStore.segmentExists(
        session.getSessionId(), SegmentNames.siblingName(segmentName, probeIndex));
  }

  private void relocateWithLock(UUID sessionId, String segmentName) {
    withSessionLock(sessionId, () -> doRelocate(sessionId, segmentName));
  }

  private void doRelocate(UUID sessionId, String segmentName) {
    var session = runtimeRegistry.findById(sessionId).orElse(null);
    if (session == null || !requiresRelocation(session, segmentName)) {
      return;
    }

    if (segmentStore.segmentExists(sessionId, segmentName)) {
      return;
    }

    var segmentIndex = requestedIndex(session, segmentName);
    transcodeExecutor.stop(sessionId);
    startAll(session, segmentIndex * segmentDurationSeconds(), segmentIndex);
    session.setLastAccessedAt(Instant.now());
    runtimeRegistry.save(session);

    log.info("Relocated transcode for session {} to segment {}", sessionId, segmentIndex);
  }

  /**
   * The requested segment's timeline index. A name carrying none — {@code init.mp4}, which every
   * run rewrites — belongs to the current run, so it resolves to that run's start rather than to
   * segment 0, which would drag a mid-timeline producer back to the top of the file.
   */
  private int requestedIndex(StreamSession session, String segmentName) {
    return SegmentNames.indexOf(segmentName).orElseGet(() -> activeStartSequenceNumber(session));
  }

  private int activeStartSequenceNumber(StreamSession session) {
    return session.getVariantHandles().values().stream()
        .mapToInt(TranscodeHandle::startSequenceNumber)
        .max()
        .orElse(0);
  }

  private int forwardGapSegments() {
    var gapSegments =
        FORWARD_RELOCATION_GAP.toSeconds() / properties.targetSegmentDuration().toSeconds();
    return Math.max(1, (int) gapSegments);
  }

  private int segmentDurationSeconds() {
    return (int) properties.targetSegmentDuration().toSeconds();
  }

  private void doResume(UUID sessionId, String segmentName) {
    var session = runtimeRegistry.findById(sessionId).orElse(null);
    if (session == null || !session.isSuspended()) {
      return;
    }

    var segmentIndex = requestedIndex(session, segmentName);
    var resumeSeek = segmentIndex * segmentDurationSeconds();

    startAll(session, resumeSeek, segmentIndex);
    session.setLastAccessedAt(Instant.now());
    runtimeRegistry.save(session);

    log.info(
        "Resumed suspended session {} at segment {} (seek {}s)",
        sessionId,
        segmentIndex,
        resumeSeek);
  }

  private void startSingleTranscode(
      StreamSession session, int seekPosition, int startSequenceNumber) {
    var probe = session.getMediaProbe();
    var request =
        baseRequest(session, seekPosition, startSequenceNumber)
            .width(probe.width())
            .height(probe.height())
            .bitrate(probe.bitrate())
            .variantLabel(StreamSession.defaultVariant())
            .build();
    var handle = transcodeExecutor.start(request);

    session.setHandle(handle);
  }

  private void startVariantTranscodes(
      StreamSession session,
      List<QualityVariant> variants,
      int seekPosition,
      int startSequenceNumber) {
    for (var variant : variants) {
      var request =
          baseRequest(session, seekPosition, startSequenceNumber)
              .width(variant.width())
              .height(variant.height())
              .bitrate(variant.videoBitrate())
              .variantLabel(variant.label())
              .build();
      var handle = transcodeExecutor.start(request);

      session.setVariantHandle(variant.label(), handle);
    }
  }

  private TranscodeRequest.TranscodeRequestBuilder baseRequest(
      StreamSession session, int seekPosition, int startSequenceNumber) {
    return TranscodeRequest.builder()
        .sessionId(session.getSessionId())
        .sourcePath(session.getSourcePath())
        .seekPosition(seekPosition)
        .targetSegmentDuration(segmentDurationSeconds())
        .framerate(session.getMediaProbe().framerate())
        .transcodeDecision(session.getTranscodeDecision())
        .startSequenceNumber(startSequenceNumber);
  }

  private void withSessionLock(UUID sessionId, Runnable action) {
    withSessionLock(
        sessionId,
        () -> {
          action.run();
          return null;
        });
  }

  private <T> T withSessionLock(UUID sessionId, Supplier<T> action) {
    var lock = sessionMutex.getMutex(sessionId);
    lock.lock();
    try {
      return action.get();
    } finally {
      lock.unlock();
    }
  }
}
