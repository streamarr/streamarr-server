package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.concurrency.MutexFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

  /**
   * The coordinator's classification of the producer being replaced. The observation is re-verified
   * under the mutex: a DEAD claim against a producer that is actually running is a stale view and
   * must supersede, never kill.
   */
  public enum ReplacementReason {
    DEAD,
    STALLED,
    RESUME_FAILED
  }

  @Builder
  public record ReplaceProducerCommand(
      UUID sessionId,
      String variantLabel,
      String segmentName,
      int segmentIndex,
      UUID expectedAttemptId,
      ReplacementReason reason,
      ExecutionTargetId target) {

    public ReplaceProducerCommand {
      reason = reason != null ? reason : ReplacementReason.DEAD;
    }
  }

  public sealed interface ReplaceResult {
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

  /**
   * Atomically replaces one variant's producer on the given execution target. The predicate is
   * checked under the session mutex: the session must still exist, the requested segment must still
   * be absent, and the variant handle must still carry the expected attempt in a replaceable status
   * — any miss means another actor won and the caller must re-observe.
   */
  public ReplaceResult replaceProducer(ReplaceProducerCommand command) {
    return withSessionLock(command.sessionId(), () -> doReplace(command));
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

  /** Marks a variant's recovery as exhausted; cleared only by a new target or a planned seek. */
  public boolean tryMarkExhausted(UUID sessionId, String variantLabel, UUID expectedAttemptId) {
    return withSessionLock(sessionId, () -> doExhaust(sessionId, variantLabel, expectedAttemptId));
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
