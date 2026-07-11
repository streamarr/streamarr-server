package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.MaxConcurrentTranscodesException;
import com.streamarr.server.exceptions.MediaFileNotFoundException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.library.FilepathCodec;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class HlsStreamingService implements StreamingService {

  private static final Pattern SEGMENT_INDEX_PATTERN = Pattern.compile("segment(\\d+)");

  /** Beyond this lead, restarting the encoder beats waiting for it to catch up. */
  private static final Duration FORWARD_RELOCATION_GAP = Duration.ofSeconds(24);

  private final MediaFileRepository mediaFileRepository;
  private final TranscodeExecutor transcodeExecutor;
  private final SegmentStore segmentStore;
  private final FfprobeService ffprobeService;
  private final TranscodeDecisionService transcodeDecisionService;
  private final QualityLadderService qualityLadderService;
  private final StreamingProperties properties;
  private final RuntimeStreamSessionRegistry sessionRepository;
  private final MutexFactory<UUID> resumeMutex;

  @Override
  public StreamSession createSession(CreateRuntimeStreamSessionCommand command) {
    var mediaFileId = command.mediaFileId();
    var profileId = command.profileId();
    var options = command.options();
    var mediaFile =
        mediaFileRepository
            .findById(mediaFileId)
            .orElseThrow(() -> new MediaFileNotFoundException(mediaFileId));

    var probe = ffprobeService.probe(FilepathCodec.decode(mediaFile.getFilepathUri()));
    var decision = transcodeDecisionService.decide(probe, options);
    var variants = resolveVariants(probe, options, decision);
    variants = enforceCapacityLimits(decision.transcodeMode(), variants);

    var sessionId = command.streamSessionId();
    var now = Instant.now();

    var session =
        StreamSession.builder()
            .sessionId(sessionId)
            .mediaFileId(mediaFileId)
            .profileId(profileId)
            .sourcePath(FilepathCodec.decode(mediaFile.getFilepathUri()))
            .mediaProbe(probe)
            .transcodeDecision(decision)
            .options(options)
            .variants(variants)
            .createdAt(now)
            .build();
    session.setLastAccessedAt(command.initialLastAccessedAt());

    if (!sessionRepository.attach(command.reservation(), session)) {
      throw new com.streamarr.server.exceptions.SessionNotFoundException(sessionId);
    }
    startTranscodes(session, 0, 0);
    log.info(
        "Created streaming session {} for media file {} (mode: {}, variants: {})",
        sessionId,
        mediaFileId,
        decision.transcodeMode(),
        variants.size());

    return session;
  }

  @Override
  public Optional<StreamSession> accessSession(UUID sessionId) {
    return sessionRepository.findById(sessionId);
  }

  @Override
  public void destroySession(UUID sessionId) {
    if (!sessionRepository.terminalize(sessionId)) {
      return;
    }
    terminateRuntime(sessionId);
  }

  @Override
  public boolean terminateRuntime(UUID sessionId) {
    sessionRepository.terminalize(sessionId);
    transcodeExecutor.stop(sessionId);
    sessionRepository.markRuntimeStopped(sessionId);
    segmentStore.deleteSession(sessionId);
    var quiescent = sessionRepository.releaseTerminal(sessionId);
    log.info("Destroyed streaming session {}", sessionId);
    return quiescent;
  }

  @Override
  public void shutdownRuntime() {
    var sessionIds = sessionRepository.fenceAll();
    sessionIds.forEach(this::stopForShutdown);
    for (var sessionId : sessionIds) {
      sessionRepository.awaitTranscodeStarts(sessionId);
      stopForShutdown(sessionId);
    }
    transcodeExecutor.forceStopAll();
  }

  private void stopForShutdown(UUID sessionId) {
    try {
      transcodeExecutor.stop(sessionId);
      sessionRepository.markRuntimeStopped(sessionId);
    } catch (RuntimeException exception) {
      log.warn("Failed to stop stream session {} during shutdown", sessionId, exception);
    }
  }

  @Override
  public void destroySession(UUID sessionId, UUID profileId) {
    var session = sessionRepository.findById(sessionId);
    if (session.isEmpty()) {
      return;
    }
    // The caller still sees a plain no-op (no existence oracle); the miss is logged so
    // cross-profile attempts and wrong-owner stamping stay diagnosable server-side.
    if (!session.get().isOwnedBy(profileId)) {
      log.warn("Destroy for session {} rejected: profile {} does not own it", sessionId, profileId);
      return;
    }
    destroySession(sessionId);
  }

  @Override
  public Collection<StreamSession> getAllSessions() {
    return sessionRepository.findAll();
  }

  @Override
  public Collection<UUID> snapshotCleanupCandidateIds() {
    return sessionRepository.snapshotCleanupCandidateIds();
  }

  @Override
  public int getActiveSessionCount() {
    return sessionRepository.count();
  }

  @Override
  public void resumeSessionIfNeeded(UUID sessionId, String segmentName) {
    var session = sessionRepository.findById(sessionId).orElse(null);
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

  private void resumeWithLock(UUID sessionId, String segmentName) {
    var lock = resumeMutex.getMutex(sessionId);
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
    var startNumber = activeStartNumber(session);
    if (requestedIndex < startNumber) {
      return true;
    }

    var probeIndex = requestedIndex - forwardGapSegments();
    if (probeIndex < startNumber) {
      return false;
    }

    return !segmentStore.segmentExists(
        session.getSessionId(), siblingSegmentName(segmentName, probeIndex));
  }

  private void relocateWithLock(UUID sessionId, String segmentName) {
    var lock = resumeMutex.getMutex(sessionId);
    lock.lock();

    try {
      doRelocate(sessionId, segmentName);
    } finally {
      lock.unlock();
    }
  }

  private void doRelocate(UUID sessionId, String segmentName) {
    var session = sessionRepository.findById(sessionId).orElse(null);
    if (session == null || !requiresRelocation(session, segmentName)) {
      return;
    }

    if (segmentStore.segmentExists(sessionId, segmentName)) {
      return;
    }

    var segmentIndex = parseSegmentIndex(segmentName);
    transcodeExecutor.stop(sessionId);
    startTranscodes(session, segmentIndex * segmentDurationSeconds(), segmentIndex);
    log.info("Relocated transcode for session {} to segment {}", sessionId, segmentIndex);
  }

  private int activeStartNumber(StreamSession session) {
    return session.getVariantHandles().values().stream()
        .mapToInt(TranscodeHandle::startNumber)
        .max()
        .orElse(0);
  }

  private int forwardGapSegments() {
    var gapSegments = FORWARD_RELOCATION_GAP.toSeconds() / properties.segmentDuration().toSeconds();
    return Math.max(1, (int) gapSegments);
  }

  private int segmentDurationSeconds() {
    return (int) properties.segmentDuration().toSeconds();
  }

  private static String siblingSegmentName(String segmentName, int index) {
    return SEGMENT_INDEX_PATTERN.matcher(segmentName).replaceFirst("segment" + index);
  }

  private void doResume(UUID sessionId, String segmentName) {
    var session = sessionRepository.findById(sessionId).orElse(null);
    if (session == null || !session.isSuspended()) {
      return;
    }

    var segmentIndex = parseSegmentIndex(segmentName);
    var resumeSeek = segmentIndex * (int) properties.segmentDuration().toSeconds();

    startTranscodes(session, resumeSeek, segmentIndex);
    log.info(
        "Resumed suspended session {} at segment {} (seek {}s)",
        sessionId,
        segmentIndex,
        resumeSeek);
  }

  private List<QualityVariant> resolveVariants(
      MediaProbe probe, StreamingOptions options, TranscodeDecision decision) {
    if (!isAutoQuality(options) || !requiresVideoTranscode(decision.transcodeMode())) {
      return Collections.emptyList();
    }

    return qualityLadderService.generateVariants(probe, options);
  }

  private boolean requiresVideoTranscode(TranscodeMode mode) {
    return mode == TranscodeMode.VIDEO_TRANSCODE || mode == TranscodeMode.FULL_TRANSCODE;
  }

  private boolean isAutoQuality(StreamingOptions options) {
    return options.quality() == null || options.quality() == VideoQuality.AUTO;
  }

  private List<QualityVariant> enforceCapacityLimits(
      TranscodeMode mode, List<QualityVariant> variants) {
    if (!variants.isEmpty()) {
      return capVariantsToAvailableSlots(variants);
    }

    if (requiresTranscode(mode)) {
      enforceTranscodeLimit();
    }

    return variants;
  }

  private List<QualityVariant> capVariantsToAvailableSlots(List<QualityVariant> variants) {
    var slotsAvailable = availableTranscodeSlots();
    if (slotsAvailable <= 0) {
      throw new MaxConcurrentTranscodesException(properties.maxConcurrentTranscodes());
    }

    if (variants.size() > slotsAvailable) {
      return variants.subList(0, slotsAvailable);
    }

    return variants;
  }

  private int availableTranscodeSlots() {
    var activeTranscodes =
        sessionRepository.findAll().stream()
            .filter(s -> !s.isSuspended())
            .filter(s -> requiresTranscode(s.getTranscodeDecision().transcodeMode()))
            .mapToInt(s -> Math.max(1, s.getVariants().size()))
            .sum();
    return properties.maxConcurrentTranscodes() - activeTranscodes;
  }

  private boolean requiresTranscode(TranscodeMode mode) {
    return mode != TranscodeMode.REMUX;
  }

  private void enforceTranscodeLimit() {
    if (availableTranscodeSlots() <= 0) {
      throw new MaxConcurrentTranscodesException(properties.maxConcurrentTranscodes());
    }
  }

  private void startTranscodes(StreamSession session, int seekPosition, int startNumber) {
    if (session.getVariants().isEmpty()) {
      startSingleTranscode(session, seekPosition, startNumber);
      return;
    }

    startVariantTranscodes(session, session.getVariants(), seekPosition, startNumber);
  }

  private void startSingleTranscode(StreamSession session, int seekPosition, int startNumber) {
    var probe = session.getMediaProbe();
    var request =
        TranscodeRequest.builder()
            .sessionId(session.getSessionId())
            .sourcePath(session.getSourcePath())
            .seekPosition(seekPosition)
            .segmentDuration((int) properties.segmentDuration().toSeconds())
            .framerate(probe.framerate())
            .transcodeDecision(session.getTranscodeDecision())
            .width(probe.width())
            .height(probe.height())
            .bitrate(probe.bitrate())
            .variantLabel(StreamSession.defaultVariant())
            .startNumber(startNumber)
            .build();
    var handle = startTranscode(request);

    session.setHandle(handle);
  }

  private void startVariantTranscodes(
      StreamSession session, List<QualityVariant> variants, int seekPosition, int startNumber) {
    for (var variant : variants) {
      var request =
          TranscodeRequest.builder()
              .sessionId(session.getSessionId())
              .sourcePath(session.getSourcePath())
              .seekPosition(seekPosition)
              .segmentDuration((int) properties.segmentDuration().toSeconds())
              .framerate(session.getMediaProbe().framerate())
              .transcodeDecision(session.getTranscodeDecision())
              .width(variant.width())
              .height(variant.height())
              .bitrate(variant.videoBitrate())
              .variantLabel(variant.label())
              .startNumber(startNumber)
              .build();
      var handle = startTranscode(request);

      session.setVariantHandle(variant.label(), handle);
    }
  }

  private TranscodeHandle startTranscode(TranscodeRequest request) {
    var start =
        sessionRepository
            .beginTranscodeStart(request.sessionId())
            .orElseThrow(
                () ->
                    new com.streamarr.server.exceptions.SessionNotFoundException(
                        request.sessionId()));
    TranscodeHandle handle;
    try {
      handle = transcodeExecutor.start(request);
    } catch (RuntimeException exception) {
      sessionRepository.abortTranscodeStart(start);
      throw exception;
    }
    if (sessionRepository.completeTranscodeStart(start)) {
      return handle;
    }
    finishRejectedStart(start);
    throw new com.streamarr.server.exceptions.SessionNotFoundException(request.sessionId());
  }

  private void finishRejectedStart(RuntimeTranscodeStart start) {
    try {
      transcodeExecutor.stop(start.sessionId());
      sessionRepository.finishRejectedTranscodeStart(start, true);
    } catch (RuntimeException exception) {
      sessionRepository.finishRejectedTranscodeStart(start, false);
      throw exception;
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
