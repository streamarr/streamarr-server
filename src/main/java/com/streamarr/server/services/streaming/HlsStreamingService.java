package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.mutation.Outcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Builder
public class HlsStreamingService implements StreamingService {

  private static final String CAPACITY_REFUSALS_METRIC = "streamarr.streaming.capacity_refusals";

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
  private final MeterRegistry meterRegistry;

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
    if (MediaSegmentTimelines.of(probe, properties).mediaSegmentCount() == 0) {
      return Outcome.rejected(new CreateStreamSessionRejection.NoMediaSegments());
    }

    var picture = probe.displayedSize();
    if (picture.width() <= 0 || picture.height() <= 0) {
      return Outcome.rejected(new CreateStreamSessionRejection.InvalidPictureSize());
    }

    var authority = command.identity().playbackAuthority();
    var mediaFileId = command.mediaFileId();
    var options = command.options();
    var request =
        TranscodeDecisionRequest.builder()
            .source(probe)
            .capabilityDeclaration(command.capabilityDeclaration())
            .videoFormatConstraint(command.videoFormatConstraint())
            .excludedEncodeCodecFamilies(command.excludedEncodeCodecFamilies())
            .encodableVideoCodecFamilies(transcodeExecutor.encodableVideoCodecFamilies())
            .build();
    var candidate = transcodeDecisionService.decide(request);
    if (candidate.isEmpty()) {
      candidate = decideAfterStoppingALeftVariant(command, request);
    }

    if (candidate.isEmpty()) {
      return Outcome.rejected(unavailableVideoFormat(request));
    }

    var decision = candidate.orElseThrow();
    var encodeRejection = encodeRejection(command, request, decision);
    if (encodeRejection.isPresent()) {
      return Outcome.rejected(encodeRejection.get());
    }

    var variants = resolveVariants(command, probe, decision);
    var sessionId = command.streamSessionId();
    var refusal = capacityRefusalFor(decision);
    // A left variant only serves its own player's switch back, so it never keeps a new stream
    // session out (ADR 0019).
    if (refusal.isPresent() && isSlotShortage(refusal.get())) {
      producerLifecycle.stopLeftVariantOfAnotherStreamSession(sessionId, decision);
      refusal = capacityRefusalFor(decision);
    }

    if (refusal.isPresent()) {
      return Outcome.rejected(refuseForLackOfCapacity(refusal.get()));
    }

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
      session = producerLifecycle.startFirstListedVariant(session);
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

  // Why no worker can run the decision's encode, whatever slots are free.
  private Optional<CreateStreamSessionRejection> encodeRejection(
      CreateStreamSessionCommand command,
      TranscodeDecisionRequest request,
      TranscodeDecision decision) {
    if (!requiresVideoTranscode(decision.transcodeMode())) {
      return Optional.empty();
    }

    // Workers encode at the frame-rate rule's output frame rate and refuse an encode without one.
    if (decision.outputFrameRate().isEmpty()) {
      return Optional.of(new CreateStreamSessionRejection.FrameRateUnknown());
    }

    // An encode whose ladder has no variant has no picture that the worker encodes.
    var probe = request.source();
    var variants = resolveVariants(command, probe, decision);
    if (variants.isEmpty()) {
      return Optional.of(new CreateStreamSessionRejection.InvalidPictureSize());
    }

    return Optional.empty();
  }

  // An encode whose encoders all run on workers with no free slot is decided only once a slot is
  // free. A left variant never keeps a new stream session out (ADR 0019), so the server stops one
  // that frees a slot on such a worker and decides once more. An encode that no worker can run
  // whatever slots are free stops nothing.
  private Optional<TranscodeDecision> decideAfterStoppingALeftVariant(
      CreateStreamSessionCommand command, TranscodeDecisionRequest request) {
    var verifiedCandidate =
        verifiedEncodeCandidate(request)
            .filter(decision -> encodeRejection(command, request, decision).isEmpty());
    if (verifiedCandidate.isEmpty()) {
      return Optional.empty();
    }

    // The first decision already refused a copy, so only an encode is decided again.
    producerLifecycle.stopLeftVariantOfAnotherStreamSession(
        command.streamSessionId(), verifiedCandidate.get());
    return transcodeDecisionService.decide(
        request.toBuilder()
            .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
            .encodableVideoCodecFamilies(transcodeExecutor.encodableVideoCodecFamilies())
            .build());
  }

  private CreateStreamSessionRejection unavailableVideoFormat(TranscodeDecisionRequest request) {
    if (!transcodeExecutor.isHealthy()) {
      return refuseForLackOfCapacity(CapacityRefusal.NO_CONNECTED_WORKER);
    }

    if (verifiedEncodeCandidate(request).isPresent()) {
      return refuseForLackOfCapacity(CapacityRefusal.NO_ENCODER_WITH_FREE_SLOT);
    }

    return new CreateStreamSessionRejection.NoEligibleVideoFormat();
  }

  // The encode that a connected worker's verified encoder could run once it had a free slot.
  private Optional<TranscodeDecision> verifiedEncodeCandidate(TranscodeDecisionRequest request) {
    if (!transcodeExecutor.isHealthy()
        || request.videoFormatConstraint() == VideoFormatConstraint.COPY_ONLY) {
      return Optional.empty();
    }

    return transcodeDecisionService.decide(
        request.toBuilder()
            .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
            .encodableVideoCodecFamilies(transcodeExecutor.verifiedVideoCodecFamilies())
            .build());
  }

  private CreateStreamSessionRejection refuseForLackOfCapacity(CapacityRefusal refusal) {
    meterRegistry
        .counter(CAPACITY_REFUSALS_METRIC, "cause", refusal.name().toLowerCase(Locale.ROOT))
        .increment();
    return new CreateStreamSessionRejection.TranscodeCapacityUnavailable(
        properties.maxConcurrentTranscodes());
  }

  private void rollbackFailedStartup(UUID sessionId, RuntimeException startupFailure) {
    suppressCleanupFailure(startupFailure, () -> transcodeExecutor.destroySession(sessionId));
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

  // Every encode runs as a variant of the ladder; a copy keeps the source and has no ladder.
  private List<QualityVariant> resolveVariants(
      CreateStreamSessionCommand command, MediaProbe probe, TranscodeDecision decision) {
    if (!requiresVideoTranscode(decision.transcodeMode())) {
      return List.of();
    }

    // The stream session refuses an encode without an output frame rate before it sizes a variant.
    var request =
        QualityLadderRequest.builder()
            .probe(probe)
            .options(command.options())
            .capabilityDeclaration(command.capabilityDeclaration())
            .codecProfile(EncodeCodecProfile.forCodecFamilyName(decision.videoCodecFamily()))
            .frameRate(decision.outputFrameRate().orElseThrow())
            .build();
    return MultivariantPlaylistOrder.of(
        qualityLadderService.generateVariants(request), decision.audioDecision().bitrate());
  }

  private boolean requiresVideoTranscode(TranscodeMode mode) {
    return mode == TranscodeMode.VIDEO_TRANSCODE || mode == TranscodeMode.FULL_TRANSCODE;
  }

  // Creating a stream session starts one job attempt, so a session needs one free slot whatever
  // the size of its ladder.
  private Optional<CapacityRefusal> capacityRefusalFor(TranscodeDecision decision) {
    if (!transcodeExecutor.isHealthy()) {
      return Optional.of(CapacityRefusal.NO_CONNECTED_WORKER);
    }

    if (transcodeExecutor.availableSlots(decision) <= 0) {
      return Optional.of(CapacityRefusal.NO_FREE_WORKER_SLOT);
    }

    if (requiresTranscode(decision.transcodeMode())
        && producerLifecycle.freeConfiguredTranscodeSlots() <= 0) {
      return Optional.of(CapacityRefusal.NO_FREE_CONFIGURED_TRANSCODE_SLOT);
    }

    return Optional.empty();
  }

  private static boolean isSlotShortage(CapacityRefusal refusal) {
    return switch (refusal) {
      case NO_FREE_WORKER_SLOT, NO_FREE_CONFIGURED_TRANSCODE_SLOT -> true;
      case NO_CONNECTED_WORKER, NO_ENCODER_WITH_FREE_SLOT -> false;
    };
  }

  // Why a stream session could not start now, as the capacity refusal metric names it.
  private enum CapacityRefusal {
    NO_CONNECTED_WORKER,
    NO_ENCODER_WITH_FREE_SLOT,
    NO_FREE_WORKER_SLOT,
    NO_FREE_CONFIGURED_TRANSCODE_SLOT
  }

  private boolean requiresTranscode(TranscodeMode mode) {
    return mode != TranscodeMode.REMUX;
  }
}
