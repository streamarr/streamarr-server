package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.MaxConcurrentTranscodesException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.mutation.Outcome;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Builder
public class HlsStreamingService implements StreamingService {

  private final MediaFileRepository mediaFileRepository;
  private final TranscodeExecutor transcodeExecutor;
  private final SegmentStore segmentStore;
  private final PlaybackProbeService playbackProbeService;
  private final TranscodeDecisionService transcodeDecisionService;
  private final QualityLadderService qualityLadderService;
  private final StreamingProperties properties;
  private final PlaybackAuthorityGate authorityGate;
  private final RuntimeStreamSessionRegistry runtimeRegistry;
  private final ProducerLifecycleService producerLifecycle;
  private final SegmentDeliveryCoordinator deliveryCoordinator;

  @Override
  public Outcome<StreamSession, CreateStreamSessionRejection> createSession(
      CreateStreamSessionCommand command) {
    var identity = command.identity();
    if (identity.profileId() == null
        || !authorityGate.allows(identity, identity.playbackAuthority())) {
      throw new AuthenticationRequiredException();
    }

    var mediaFileId = command.mediaFileId();
    var mediaFile = mediaFileRepository.findById(mediaFileId);
    if (mediaFile.isEmpty()) {
      return Outcome.rejected(new CreateStreamSessionRejection.MediaFileNotFound(mediaFileId));
    }

    return playbackProbeService
        .read(mediaFileId)
        .fold(probe -> startSession(command, mediaFile.get(), probe), Outcome::rejected);
  }

  private Outcome<StreamSession, CreateStreamSessionRejection> startSession(
      CreateStreamSessionCommand command, MediaFile mediaFile, MediaProbe probe) {
    var authority = command.identity().playbackAuthority();
    var mediaFileId = command.mediaFileId();
    var options = command.options();
    var decision = transcodeDecisionService.decide(probe, options);
    var variants = resolveVariants(probe, options, decision);
    try {
      variants = enforceCapacityLimits(decision.transcodeMode(), variants);
    } catch (MaxConcurrentTranscodesException _) {
      return Outcome.rejected(
          new CreateStreamSessionRejection.TranscodeCapacityUnavailable(
              properties.maxConcurrentTranscodes()));
    }

    var sessionId = UUID.randomUUID();
    var now = Instant.now();

    var session =
        StreamSession.builder()
            .sessionId(sessionId)
            .mediaFileId(mediaFileId)
            .authority(authority)
            .sourcePath(FilepathCodec.decode(mediaFile.getFilepathUri()))
            .mediaProbe(probe)
            .transcodeDecision(decision)
            .options(options)
            .variants(variants)
            .createdAt(now)
            .build();

    try {
      runtimeRegistry.save(session);
      producerLifecycle.startAll(session, 0, 0);
      runtimeRegistry.save(session);
    } catch (RuntimeException startupFailure) {
      rollbackFailedStartup(sessionId, startupFailure);
      throw startupFailure;
    }
    log.info(
        "Created streaming session {} for media file {} (mode: {}, variants: {})",
        sessionId,
        mediaFileId,
        decision.transcodeMode(),
        variants.size());

    return Outcome.accepted(session);
  }

  private void rollbackFailedStartup(UUID sessionId, RuntimeException startupFailure) {
    suppressCleanupFailure(startupFailure, () -> transcodeExecutor.stop(sessionId));
    suppressCleanupFailure(startupFailure, () -> segmentStore.deleteSession(sessionId));
    suppressCleanupFailure(startupFailure, () -> runtimeRegistry.removeById(sessionId));
  }

  private static void suppressCleanupFailure(RuntimeException startupFailure, Runnable cleanup) {
    try {
      cleanup.run();
    } catch (RuntimeException cleanupFailure) {
      startupFailure.addSuppressed(cleanupFailure);
    }
  }

  @Override
  public Optional<StreamSession> accessSession(PlaybackRequest request) {
    var session = runtimeRegistry.findById(request.streamSessionId());
    if (session.isEmpty()) {
      return Optional.empty();
    }

    var identity = request.identity();
    var authority = session.get().getAuthority();
    if (identity.profileId() == null || !identity.playbackAuthority().equals(authority)) {
      return Optional.empty();
    }

    if (!authorityGate.allows(identity, authority)) {
      return Optional.empty();
    }

    runtimeRegistry.touch(request.streamSessionId(), Instant.now());
    return session;
  }

  @Override
  public void destroySession(UUID sessionId) {
    if (producerLifecycle.removeSession(sessionId)) {
      cleanUpDestroyed(sessionId);
    }
  }

  private void cleanUpDestroyed(UUID sessionId) {
    try {
      producerLifecycle.stopForDestroy(sessionId);
    } finally {
      try {
        // The session is already unreachable; a failed stop must not orphan its segments.
        segmentStore.deleteSession(sessionId);
      } finally {
        deliveryCoordinator.forgetSession(sessionId);
      }
    }
    log.info("Destroyed streaming session {}", sessionId);
  }

  @Override
  public void destroySession(UUID sessionId, UUID profileId) {
    var session = runtimeRegistry.findById(sessionId);
    if (session.isEmpty()) {
      return;
    }
    // Leave the session unchanged and return normally to conceal its existence from other
    // Profiles. Log the ownership mismatch so operators can diagnose incorrect access attempts.
    if (!session.get().isOwnedBy(profileId)) {
      log.warn("Destroy for session {} rejected: profile {} does not own it", sessionId, profileId);
      return;
    }
    destroySession(sessionId);
  }

  @Override
  public Collection<StreamSession> getAllSessions() {
    return runtimeRegistry.findAll();
  }

  @Override
  public int getActiveSessionCount() {
    return runtimeRegistry.count();
  }

  private List<QualityVariant> resolveVariants(
      MediaProbe probe, StreamingOptions options, TranscodeDecision decision) {
    if (!isAutoQuality(options) || !requiresVideoTranscode(decision.transcodeMode())) {
      return List.of();
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
    if (transcodeExecutor.availableSlots() <= 0) {
      throw new MaxConcurrentTranscodesException(properties.maxConcurrentTranscodes());
    }

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
        runtimeRegistry.findAll().stream()
            .filter(s -> !s.isSuspended())
            .filter(s -> requiresTranscode(s.getTranscodeDecision().transcodeMode()))
            .mapToInt(s -> Math.max(1, s.getVariants().size()))
            .sum();
    var configuredSlots = properties.maxConcurrentTranscodes() - activeTranscodes;
    return Math.min(configuredSlots, transcodeExecutor.availableSlots());
  }

  private boolean requiresTranscode(TranscodeMode mode) {
    return mode != TranscodeMode.REMUX;
  }

  private void enforceTranscodeLimit() {
    if (availableTranscodeSlots() <= 0) {
      throw new MaxConcurrentTranscodesException(properties.maxConcurrentTranscodes());
    }
  }
}
