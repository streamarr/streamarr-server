package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.services.concurrency.MutexFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns every producer (transcode process) mutation for a stream session: initial start, resume of a
 * suspended session, relocation to a distant segment, and suspension. All mutations serialize on
 * the per-session mutex this service holds.
 */
@Slf4j
@Builder
public class ProducerLifecycleService {

  private static final Pattern SEGMENT_INDEX_PATTERN = Pattern.compile("segment(\\d+)");

  /** Beyond this lead, restarting the encoder beats waiting for it to catch up. */
  private static final Duration FORWARD_RELOCATION_GAP = Duration.ofSeconds(24);

  private final TranscodeExecutor transcodeExecutor;
  private final SegmentStore segmentStore;
  private final StreamingProperties properties;
  private final RuntimeStreamSessionRegistry runtimeRegistry;
  private final MutexFactory<UUID> sessionMutex;

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
    var requestedIndex = parseSegmentIndex(segmentName);
    var startSequenceNumber = activeStartSequenceNumber(session);
    if (requestedIndex < startSequenceNumber) {
      return true;
    }

    var probeIndex = requestedIndex - forwardGapSegments();
    if (probeIndex < startSequenceNumber) {
      return false;
    }

    return !segmentStore.segmentExists(
        session.getSessionId(), siblingSegmentName(segmentName, probeIndex));
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

    var segmentIndex = parseSegmentIndex(segmentName);
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

  private static String siblingSegmentName(String segmentName, int index) {
    return SEGMENT_INDEX_PATTERN.matcher(segmentName).replaceFirst("segment" + index);
  }

  private void doResume(UUID sessionId, String segmentName) {
    var session = runtimeRegistry.findById(sessionId).orElse(null);
    if (session == null || !session.isSuspended()) {
      return;
    }

    var segmentIndex = parseSegmentIndex(segmentName);
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

  private static int parseSegmentIndex(String segmentName) {
    var basename = segmentName;
    var slashIdx = basename.lastIndexOf('/');
    if (slashIdx >= 0) {
      basename = basename.substring(slashIdx + 1);
    }

    var matcher = SEGMENT_INDEX_PATTERN.matcher(basename);
    if (!matcher.find()) {
      return 0;
    }

    return Integer.parseInt(matcher.group(1));
  }
}
