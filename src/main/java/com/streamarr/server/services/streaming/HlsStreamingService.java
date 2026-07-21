package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.MaxConcurrentTranscodesException;
import com.streamarr.server.exceptions.MediaFileNotFoundException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.library.FilepathCodec;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
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
  private final FfprobeService ffprobeService;
  private final TranscodeDecisionService transcodeDecisionService;
  private final QualityLadderService qualityLadderService;
  private final StreamingProperties properties;
  private final PlaybackAuthorityGate authorityGate;
  private final RuntimeStreamSessionRegistry runtimeRegistry;
  private final ProducerLifecycleService producerLifecycle;
  private final SegmentDeliveryCoordinator deliveryCoordinator;

  @Override
  public StreamSession createSession(CreateStreamSessionCommand command) {
    if (!authorityGate.allows(command.authority())) {
      throw new AuthenticationRequiredException();
    }

    var mediaFileId = command.mediaFileId();
    var options = command.options();
    var mediaFile =
        mediaFileRepository
            .findById(mediaFileId)
            .orElseThrow(() -> new MediaFileNotFoundException(mediaFileId));

    var probe = ffprobeService.probe(FilepathCodec.decode(mediaFile.getFilepathUri()));
    var decision = transcodeDecisionService.decide(probe, options);
    var variants = resolveVariants(probe, options, decision);
    variants = enforceCapacityLimits(decision.transcodeMode(), variants);

    var sessionId = UUID.randomUUID();
    var now = Instant.now();

    var session =
        StreamSession.builder()
            .sessionId(sessionId)
            .mediaFileId(mediaFileId)
            .authority(command.authority())
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

    return session;
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
    if (session.isEmpty() || !request.authority().equals(session.get().getAuthority())) {
      return Optional.empty();
    }
    if (!authorityGate.allows(request.authority())) {
      return Optional.empty();
    }

    session.get().setLastAccessedAt(Instant.now());
    runtimeRegistry.save(session.get());
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
    return runtimeRegistry.findAll();
  }

  @Override
  public int getActiveSessionCount() {
    return runtimeRegistry.count();
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
