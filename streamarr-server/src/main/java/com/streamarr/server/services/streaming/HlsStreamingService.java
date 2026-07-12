package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.MaxConcurrentTranscodesException;
import com.streamarr.server.exceptions.MediaFileNotFoundException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.library.FilepathCodec;
import com.streamarr.server.services.streaming.source.MediaSourceCatalog;
import com.streamarr.transcode.engine.model.QualityVariant;
import com.streamarr.transcode.engine.model.RenditionSpec;
import com.streamarr.transcode.engine.model.TranscodeDecision;
import com.streamarr.transcode.engine.model.TranscodeExecutionParameters;
import com.streamarr.transcode.engine.model.TranscodeJobState;
import com.streamarr.transcode.engine.model.TranscodeMode;
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
  private static final double FALLBACK_VIDEO_FRAMERATE = 30.0;

  /** Beyond this lead, restarting the encoder beats waiting for it to catch up. */
  private static final Duration FORWARD_RELOCATION_GAP = Duration.ofSeconds(24);

  private final MediaFileRepository mediaFileRepository;
  private final SegmentStore segmentStore;
  private final FfprobeService ffprobeService;
  private final TranscodeDecisionService transcodeDecisionService;
  private final QualityLadderService qualityLadderService;
  private final StreamingProperties properties;
  private final RuntimeStreamSessionRegistry sessionRepository;
  private final MutexFactory<UUID> resumeMutex;
  private final PlaybackTranscodeJobService playbackTranscodeJobService;
  private final MediaSourceCatalog mediaSourceCatalog;
  private final TranscodeCapacityTracker transcodeCapacityTracker;

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
    return createSession(command, probe, decision);
  }

  private StreamSession createSession(
      CreateRuntimeStreamSessionCommand command, MediaProbe probe, TranscodeDecision decision) {
    var mediaFileId = command.mediaFileId();
    var profileId = command.profileId();
    var options = command.options();
    var variants = resolveVariants(probe, options, decision);
    variants =
        claimTranscodeCapacity(command.streamSessionId(), decision.transcodeMode(), variants);

    var sessionId = command.streamSessionId();
    var now = Instant.now();

    var session =
        StreamSession.builder()
            .sessionId(sessionId)
            .mediaFileId(mediaFileId)
            .profileId(profileId)
            .mediaProbe(probe)
            .transcodeDecision(decision)
            .options(options)
            .variants(variants)
            .createdAt(now)
            .build();
    session.setLastAccessedAt(command.initialLastAccessedAt());

    if (!sessionRepository.attach(command.reservation(), session)) {
      transcodeCapacityTracker.release(sessionId);
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
    if (playbackTranscodeJobService.cleanupTerminal(sessionId) == RuntimeTranscodeCleanup.PENDING) {
      log.warn("Cleanup remains pending for streaming session {}", sessionId);
      return false;
    }
    transcodeCapacityTracker.release(sessionId);
    segmentStore.deleteSession(sessionId);
    var quiescent = sessionRepository.releaseTerminal(sessionId);
    log.info("Destroyed streaming session {}", sessionId);
    return quiescent;
  }

  @Override
  public void shutdownRuntime() {
    var sessionIds = sessionRepository.fenceAll();
    for (var sessionId : sessionIds) {
      try {
        var cleanup = playbackTranscodeJobService.cleanupTerminal(sessionId);
        if (cleanup == RuntimeTranscodeCleanup.PENDING) {
          log.warn("Cleanup remains pending for stream session {} during shutdown", sessionId);
          continue;
        }
        transcodeCapacityTracker.release(sessionId);
      } catch (RuntimeException exception) {
        log.warn("Failed to clean stream session {} during shutdown", sessionId, exception);
      }
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
    var lock = resumeMutex.getMutex(sessionId);
    lock.lock();
    try {
      resumeOrRelocate(sessionId, segmentName);
    } finally {
      lock.unlock();
    }
  }

  private void resumeOrRelocate(UUID sessionId, String segmentName) {
    var session = sessionRepository.findById(sessionId).orElse(null);
    if (session == null || segmentStore.segmentExists(sessionId, segmentName)) {
      return;
    }

    switch (playbackTranscodeJobService.inspectActive(sessionId)) {
      case ActiveTranscodeJobInspection.None _ -> startAtRequestedSegment(session, segmentName);
      case ActiveTranscodeJobInspection.Unavailable(var jobRef) ->
          log.warn(
              "Active transcode inspection unavailable for session {} job {}", sessionId, jobRef);
      case ActiveTranscodeJobInspection.Observed(var observation, var startNumber) ->
          resumeObserved(session, segmentName, observation.state(), startNumber);
    }
  }

  private void resumeObserved(
      StreamSession session, String segmentName, TranscodeJobState state, int startNumber) {
    var shouldRestart =
        switch (state) {
          case ADMITTING, RUNNING ->
              requiresRelocation(session.getSessionId(), segmentName, startNumber);
          case COMPLETED, FAILED, STOPPED, ABSENT -> true;
        };
    if (!shouldRestart) {
      return;
    }
    if (state == TranscodeJobState.FAILED) {
      log.warn("Transcode failed for session {}; replacing missing output", session.getSessionId());
    }
    startAtRequestedSegment(session, segmentName);
  }

  /**
   * A running encoder produces segments sequentially from its start segment. A requested segment
   * needs the encoder relocated when it lies behind that start (it will never be produced) or so
   * far ahead of produced output that waiting would stall the player longer than restarting.
   */
  private boolean requiresRelocation(UUID sessionId, String segmentName, int startNumber) {
    var requestedIndex = parseSegmentIndex(segmentName);
    if (requestedIndex < startNumber) {
      return true;
    }

    var probeIndex = requestedIndex - forwardGapSegments();
    if (probeIndex < startNumber) {
      return false;
    }

    return !segmentStore.segmentExists(sessionId, siblingSegmentName(segmentName, probeIndex));
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

  private void startAtRequestedSegment(StreamSession session, String segmentName) {
    var segmentIndex = parseSegmentIndex(segmentName);
    var resumeSeek = segmentIndex * segmentDurationSeconds();

    claimExactTranscodeCapacity(session);
    startTranscodes(session, resumeSeek, segmentIndex);
    log.info(
        "Started replacement transcode for session {} at segment {} (seek {}s)",
        session.getSessionId(),
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

  private List<QualityVariant> claimTranscodeCapacity(
      UUID sessionId, TranscodeMode mode, List<QualityVariant> variants) {
    if (!requiresTranscode(mode)) {
      return variants;
    }
    reconcileInactiveTranscodeCapacity();
    var requestedSlots = Math.max(1, variants.size());
    var admittedSlots =
        transcodeCapacityTracker.claimUpTo(
            sessionId, requestedSlots, properties.maxConcurrentTranscodes());
    if (admittedSlots == 0) {
      throw new MaxConcurrentTranscodesException(properties.maxConcurrentTranscodes());
    }
    if (variants.size() > admittedSlots) {
      return variants.subList(0, admittedSlots);
    }
    return variants;
  }

  private void claimExactTranscodeCapacity(StreamSession session) {
    if (!requiresTranscode(session.getTranscodeDecision().transcodeMode())) {
      return;
    }
    var slots = Math.max(1, session.getVariants().size());
    if (!transcodeCapacityTracker.claimExact(
        session.getSessionId(), slots, properties.maxConcurrentTranscodes())) {
      throw new MaxConcurrentTranscodesException(properties.maxConcurrentTranscodes());
    }
  }

  private void reconcileInactiveTranscodeCapacity() {
    for (var claim : transcodeCapacityTracker.activeClaims()) {
      if (!consumesTranscodeCapacity(claim.sessionId())) {
        transcodeCapacityTracker.releaseActive(claim);
      }
    }
  }

  private boolean consumesTranscodeCapacity(UUID sessionId) {
    return switch (playbackTranscodeJobService.inspectActive(sessionId)) {
      case ActiveTranscodeJobInspection.None _ -> false;
      case ActiveTranscodeJobInspection.Unavailable _ -> true;
      case ActiveTranscodeJobInspection.Observed(var observation, _) ->
          observation.state() == TranscodeJobState.ADMITTING
              || observation.state() == TranscodeJobState.RUNNING;
    };
  }

  private boolean requiresTranscode(TranscodeMode mode) {
    return mode != TranscodeMode.REMUX;
  }

  private void startTranscodes(StreamSession session, int seekPosition, int startNumber) {
    try {
      playbackTranscodeJobService.start(
          StartPlaybackTranscodeJobCommand.builder()
              .sessionId(session.getSessionId())
              .source(mediaSourceCatalog.referenceFor(session.getMediaFileId()))
              .decision(session.getTranscodeDecision())
              .execution(
                  TranscodeExecutionParameters.builder()
                      .seekPosition(seekPosition)
                      .segmentDuration(segmentDurationSeconds())
                      .framerate(executionFramerate(session))
                      .startNumber(startNumber)
                      .startupTimeout(properties.transcodeStartupTimeout())
                      .build())
              .renditions(renditions(session))
              .build());
    } finally {
      if (requiresTranscode(session.getTranscodeDecision().transcodeMode())) {
        transcodeCapacityTracker.markActive(session.getSessionId());
      }
    }
  }

  private List<RenditionSpec> renditions(StreamSession session) {
    if (session.getVariants().isEmpty()) {
      return List.of(qualityLadderService.resolveDefaultRendition(session));
    }
    return session.getVariants().stream()
        .map(
            variant ->
                new RenditionSpec(
                    variant.label(), variant.width(), variant.height(), variant.videoBitrate()))
        .toList();
  }

  private double executionFramerate(StreamSession session) {
    var framerate = session.getMediaProbe().framerate();
    if (Double.isFinite(framerate) && framerate > 0) {
      return framerate;
    }
    if (requiresVideoTranscode(session.getTranscodeDecision().transcodeMode())) {
      return FALLBACK_VIDEO_FRAMERATE;
    }
    return 1.0;
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
