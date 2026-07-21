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
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns every producer (transcode process) mutation for a stream session: initial start, resume of a
 * suspended session, relocation to a distant segment, replacement, and suspension. Mutations of a
 * published session serialize on the per-session mutex this service holds; the initial {@link
 * #startAll} runs before the session is reachable by waiters and takes no lock.
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

  public sealed interface ExhaustResult {
    record Exhausted() implements ExhaustResult {}

    record Superseded() implements ExhaustResult {}
  }

  public void startAll(StreamSession session, int seekPosition, int startSequenceNumber) {
    if (session.getVariants().isEmpty()) {
      startSingleTranscode(session, seekPosition, startSequenceNumber);
      return;
    }

    startVariantTranscodes(session, session.getVariants(), seekPosition, startSequenceNumber);
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

  public void suspend(StreamSession session) {
    var lock = sessionMutex.getMutex(session.getSessionId());
    lock.lock();

    try {
      doSuspend(session);
    } finally {
      lock.unlock();
    }
  }

  private void doSuspend(StreamSession session) {
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
    var lock = sessionMutex.getMutex(sessionId);
    lock.lock();

    try {
      return runtimeRegistry.removeById(sessionId).isPresent();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Stops every producer of a session already removed from the registry. Taking the mutex closes
   * the window where a concurrent replace could start a producer for a destroyed session.
   */
  public void stopForDestroy(UUID sessionId) {
    var lock = sessionMutex.getMutex(sessionId);
    lock.lock();

    try {
      transcodeExecutor.stop(sessionId);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Atomically replaces one variant's producer on the given execution target. The predicate is
   * checked under the session mutex: the session must still exist, the requested segment must still
   * be absent, and the variant handle must still carry the expected attempt in a replaceable status
   * — any miss means another actor won and the caller must re-observe.
   */
  public ReplaceResult replaceProducer(ReplaceProducerCommand command) {
    var lock = sessionMutex.getMutex(command.sessionId());
    lock.lock();

    try {
      return doReplace(command);
    } finally {
      lock.unlock();
    }
  }

  private ReplaceResult doReplace(ReplaceProducerCommand command) {
    var session = runtimeRegistry.findById(command.sessionId()).orElse(null);
    if (session == null) {
      return new ReplaceResult.SessionGone();
    }

    if (segmentStore.segmentExists(command.sessionId(), command.segmentName())) {
      return new ReplaceResult.Superseded();
    }

    var handle = session.getVariantHandle(command.variantLabel());
    if (handle == null
        || !handle.attemptId().equals(command.expectedAttemptId())
        || !isReplaceableStatus(handle, session, command.reason())) {
      return new ReplaceResult.Superseded();
    }

    if (transcodeExecutor.isRunning(command.sessionId(), command.variantLabel())) {
      // Only a stall observation licenses stopping a live producer; a caller claiming death
      // against a producer that is running holds a stale view (e.g. another waiter's healthy
      // replacement) and must re-observe instead.
      if (command.reason() != ReplacementReason.STALLED) {
        return new ReplaceResult.Superseded();
      }
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
  public ExhaustResult markExhausted(UUID sessionId, String variantLabel, UUID expectedAttemptId) {
    var lock = sessionMutex.getMutex(sessionId);
    lock.lock();

    try {
      return doExhaust(sessionId, variantLabel, expectedAttemptId);
    } finally {
      lock.unlock();
    }
  }

  private ExhaustResult doExhaust(UUID sessionId, String variantLabel, UUID expectedAttemptId) {
    var session = runtimeRegistry.findById(sessionId).orElse(null);
    if (session == null) {
      return new ExhaustResult.Superseded();
    }

    // SUSPENDED is exhaustible here: with a healthy suspension every replacement supersedes
    // instead of consuming a target, so exhaustion is only ever reached when nothing — not even a
    // resume — can produce the segment (e.g. no eligible targets after a failed resume).
    var handle = session.getVariantHandle(variantLabel);
    if (handle == null || !handle.attemptId().equals(expectedAttemptId)) {
      return new ExhaustResult.Superseded();
    }

    // The last attempt can be alive but stalled; FAILED promises "no producer", so honor it.
    if (transcodeExecutor.isRunning(sessionId, variantLabel)) {
      transcodeExecutor.stopVariant(sessionId, variantLabel);
    }

    session.setVariantHandle(variantLabel, handle.withStatus(TranscodeStatus.FAILED));
    runtimeRegistry.save(session);
    return new ExhaustResult.Exhausted();
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
        TranscodeRequest.builder()
            .sessionId(session.getSessionId())
            .sourcePath(session.getSourcePath())
            .seekPosition(command.segmentIndex() * segmentDurationSeconds())
            .targetSegmentDuration(segmentDurationSeconds())
            .framerate(session.getMediaProbe().framerate())
            .transcodeDecision(session.getTranscodeDecision())
            .variantLabel(command.variantLabel())
            .startSequenceNumber(command.segmentIndex());

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
    var lock = sessionMutex.getMutex(sessionId);
    lock.lock();

    try {
      doResume(sessionId, segmentName);
    } finally {
      lock.unlock();
    }
  }

  /**
   * A running encoder produces segments sequentially from its start segment. A requested segment
   * needs the encoder relocated when it lies behind that start (it will never be produced) or so
   * far ahead of produced output that waiting would stall the player longer than restarting.
   */
  private boolean requiresRelocation(StreamSession session, String segmentName) {
    var requestedIndex = SegmentNames.parseIndex(segmentName);
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
    var lock = sessionMutex.getMutex(sessionId);
    lock.lock();

    try {
      doRelocate(sessionId, segmentName);
    } finally {
      lock.unlock();
    }
  }

  private void doRelocate(UUID sessionId, String segmentName) {
    var session = runtimeRegistry.findById(sessionId).orElse(null);
    if (session == null || !requiresRelocation(session, segmentName)) {
      return;
    }

    if (segmentStore.segmentExists(sessionId, segmentName)) {
      return;
    }

    var segmentIndex = SegmentNames.parseIndex(segmentName);
    transcodeExecutor.stop(sessionId);
    startAll(session, segmentIndex * segmentDurationSeconds(), segmentIndex);
    session.setLastAccessedAt(Instant.now());
    runtimeRegistry.save(session);

    log.info("Relocated transcode for session {} to segment {}", sessionId, segmentIndex);
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

    var segmentIndex = SegmentNames.parseIndex(segmentName);
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
        TranscodeRequest.builder()
            .sessionId(session.getSessionId())
            .sourcePath(session.getSourcePath())
            .seekPosition(seekPosition)
            .targetSegmentDuration(segmentDurationSeconds())
            .framerate(probe.framerate())
            .transcodeDecision(session.getTranscodeDecision())
            .width(probe.width())
            .height(probe.height())
            .bitrate(probe.bitrate())
            .variantLabel(StreamSession.defaultVariant())
            .startSequenceNumber(startSequenceNumber)
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
          TranscodeRequest.builder()
              .sessionId(session.getSessionId())
              .sourcePath(session.getSourcePath())
              .seekPosition(seekPosition)
              .targetSegmentDuration(segmentDurationSeconds())
              .framerate(session.getMediaProbe().framerate())
              .transcodeDecision(session.getTranscodeDecision())
              .width(variant.width())
              .height(variant.height())
              .bitrate(variant.videoBitrate())
              .variantLabel(variant.label())
              .startSequenceNumber(startSequenceNumber)
              .build();
      var handle = transcodeExecutor.start(request);

      session.setVariantHandle(variant.label(), handle);
    }
  }
}
