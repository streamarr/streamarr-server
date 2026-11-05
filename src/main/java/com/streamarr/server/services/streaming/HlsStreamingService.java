package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.language.Language;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.streaming.AudioSteps;
import com.streamarr.server.domain.streaming.AudioTrackChannels;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import com.streamarr.server.domain.streaming.EncodeFormat;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.TranscodeLimitReachedException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.mutation.Outcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Builder
public class HlsStreamingService implements StreamingService {

  private static final String CAPACITY_REFUSALS_METRIC = "streamarr.streaming.capacity_refusals";
  private static final String MISSING_ENCODER_SESSIONS_METRIC =
      "streamarr.streaming.missing_encoder_sessions";

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
  private final UploadBudget uploadBudget;
  private final MeterRegistry meterRegistry;
  private final PlaybackLanguagePreferences languagePreferences;

  @Override
  public Outcome<StreamSession, CreateStreamSessionRejection> createSession(
      CreateStreamSessionCommand command) {
    var identity = command.identity();
    if (identity.profileId() == null
        || !authorityGate.allows(identity, identity.playbackAuthority())) {
      throw new AuthenticationRequiredException();
    }

    if (command.startPosition().filter(Duration::isNegative).isPresent()) {
      return Outcome.rejected(new CreateStreamSessionRejection.StartPositionOutOfRange());
    }

    var mediaFileId = command.mediaFileId();
    var mediaFile = mediaFileRepository.findById(mediaFileId);
    if (mediaFile.isEmpty()) {
      return Outcome.rejected(new CreateStreamSessionRejection.MediaFileNotFound(mediaFileId));
    }

    return playbackProbeService
        .read(mediaFileId)
        .fold(
            probe ->
                startSession(
                    command, mediaFile.get(), withSessionAudio(probe, command, mediaFile.get())),
            Outcome::rejected);
  }

  // A track the viewer switched to, else the session's own language, then the Profile's
  // preferences for the file (ADR 0043).
  private MediaProbe withSessionAudio(
      MediaProbe probe, CreateStreamSessionCommand command, MediaFile mediaFile) {
    var position = command.audioTrackPosition();
    if (position.isPresent()) {
      return probe.withSessionAudioTrackAt(position.getAsInt());
    }

    return probe.withSessionAudio(audioLanguages(command, mediaFile));
  }

  private List<Language> audioLanguages(CreateStreamSessionCommand command, MediaFile mediaFile) {
    var languages = new ArrayList<Language>();
    Optional.ofNullable(command.options().audioLanguage()).ifPresent(languages::add);
    languages.addAll(
        languagePreferences.audioLanguagesFor(command.identity().profileId(), mediaFile));
    return languages;
  }

  private Outcome<StreamSession, CreateStreamSessionRejection> startSession(
      CreateStreamSessionCommand command, MediaFile mediaFile, MediaProbe probe) {
    if (MediaSegmentTimelines.of(probe, properties).mediaSegmentCount() == 0) {
      return Outcome.rejected(new CreateStreamSessionRejection.NoMediaSegments());
    }

    if (command
        .startPosition()
        .filter(start -> start.compareTo(probe.duration()) >= 0)
        .isPresent()) {
      return Outcome.rejected(new CreateStreamSessionRejection.StartPositionOutOfRange());
    }

    var picture = probe.displayedSize();
    if (picture.width() <= 0 || picture.height() <= 0) {
      return Outcome.rejected(new CreateStreamSessionRejection.InvalidPictureSize());
    }

    // Direct play uses no worker time, so it needs no connected worker (ADR 0045).
    var directPlay =
        transcodeDecisionService.decideDirectPlay(DirectPlayRequest.of(command, probe));
    if (directPlay instanceof DirectPlayChoice.Accepted(var container, var audioTrackPosition)) {
      var session =
          directPlaySession(command, mediaFile, probe)
              .sourceContainer(container)
              .directPlayAudioTrackPosition(audioTrackPosition)
              .build();
      runtimeRegistry.save(session);
      log.info(
          "Created direct-play streaming session {} for media file {}",
          command.streamSessionId(),
          command.mediaFileId());
      return Outcome.accepted(session);
    }

    // Without a connected worker no encoder is verified. That moment must not read as faulty
    // workers and admit a copy over the viewer's limits once a worker connects.
    var verifiedWorkers = transcodeExecutor.verifiedVideoCodecFamiliesByWorker();
    if (verifiedWorkers.isEmpty()) {
      return Outcome.rejected(refuseForLackOfCapacity(CapacityRefusal.NO_CONNECTED_WORKER));
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
            .hardwareEncodableVideoCodecFamilies(
                transcodeExecutor.hardwareEncodableVideoCodecFamilies())
            .verifiedVideoCodecFamilies(codecFamiliesOf(verifiedWorkers))
            .viewerLimits(ViewerLimits.of(options))
            .videoCopySupport(transcodeExecutor.videoCopySupportByWorker())
            .videoEncoders(encodableVideoEncoders(command.softwareEncodeCodecFamily()))
            .verifiedVideoEncoders(verifiedVideoEncoders(command.softwareEncodeCodecFamily()))
            .directPlayRefusals(directPlay.refusals())
            .softwareEncodeCodecFamily(command.softwareEncodeCodecFamily())
            .build();
    var candidate = transcodeDecisionService.decide(request);
    if (candidate.isEmpty()) {
      candidate = decideAfterStoppingALeftVariant(command.streamSessionId(), request);
    }

    if (candidate.isEmpty()) {
      return Outcome.rejected(unavailableVideoFormat(request));
    }

    var decisionWithSessionAudio = candidate.orElseThrow();
    var encodeRejection = encodeRejection(request, decisionWithSessionAudio);
    if (encodeRejection.isPresent()) {
      return Outcome.rejected(encodeRejection.get());
    }

    var admission = admission(request, decisionWithSessionAudio);
    var sessionId = command.streamSessionId();
    var refusal = capacityRefusalFor(admission.decision());
    // A left variant only serves its own player's switch back, so it never keeps a new stream
    // session out (ADR 0019).
    if (refusal.isPresent() && isSlotShortage(refusal.get())) {
      producerLifecycle.stopLeftVariantOfAnotherStreamSession(sessionId, admission.decision());
      admission = admissionWithEncodersFreeNow(request).orElse(admission);
      refusal = capacityRefusalFor(admission.decision());
    }

    if (refusal.isPresent()) {
      return Outcome.rejected(refuseForLackOfCapacity(refusal.get()));
    }

    var decision = admission.decision();
    var variants = admission.variants();
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
            .startPosition(command.startPosition().orElse(null))
            .createdAt(now)
            .networkRoute(command.networkRoute())
            .build();
    startAtTheStartSegment(session);

    try {
      runtimeRegistry.save(session);
      // When the upload budget leaves out the first-listed variant, the top variant that it keeps
      // starts instead, so the stream session holds the transcode place that admission found
      // until the player reads its playlist (ADR 0050).
      session = producerLifecycle.startListedVariant(session, uploadBudget.topKeptVariant(session));
    } catch (TranscodeLimitReachedException _) {
      // Another start took the last place under the transcode limit after this admission, so no
      // job attempt of this stream session was dispatched.
      runtimeRegistry.removeById(sessionId);
      return Outcome.rejected(
          refuseForLackOfCapacity(CapacityRefusal.NO_FREE_CONFIGURED_TRANSCODE_SLOT));
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

  // The first-listed variant's first job attempt starts at the segment that holds the start
  // position, on the stream session's own segment timeline.
  private void startAtTheStartSegment(StreamSession session) {
    var startPosition = session.getStartPosition();
    if (startPosition.isEmpty()) {
      return;
    }

    var timeline = MediaSegmentTimelines.of(session, properties);
    session.setCurrentSegment(timeline.mediaSegmentIndexAt(startPosition.get()));
  }

  // A direct-play stream session starts no job attempt, so it has no transcode decision, producer
  // or segments.
  private static StreamSession.StreamSessionBuilder directPlaySession(
      CreateStreamSessionCommand command, MediaFile mediaFile, MediaProbe probe) {
    return StreamSession.builder()
        .sessionId(command.streamSessionId())
        .mediaFileId(command.mediaFileId())
        .authority(command.identity().playbackAuthority())
        .sourcePath(FilepathCodec.decode(mediaFile.getFilepathUri()))
        .mediaProbe(probe)
        .options(command.options())
        .startPosition(command.startPosition().orElse(null))
        .networkRoute(command.networkRoute())
        .createdAt(Instant.now())
        .delivery(StreamDelivery.DIRECT_PLAY);
  }

  // The decision that a stream session starts with, and the variants of its ladder.
  private record Admission(TranscodeDecision decision, List<QualityVariant> variants) {}

  // The worker's job and the playlists read the audio step that the ladder takes (ADR 0050).
  private Admission admission(
      TranscodeDecisionRequest request, TranscodeDecision decisionWithSessionAudio) {
    var ladder = resolveLadder(request, decisionWithSessionAudio);
    return new Admission(
        decisionWithSessionAudio.withAudioDecision(ladder.audio()), ladder.variants());
  }

  // A stopped left variant's worker may encode a codec family in hardware that no free worker
  // encoded before, so the stream session is decided again against the encoders free now.
  private Optional<Admission> admissionWithEncodersFreeNow(TranscodeDecisionRequest request) {
    var freeNow = withEncodersFreeNow(request);
    return transcodeDecisionService
        .decide(freeNow)
        .filter(decision -> encodeRejection(freeNow, decision).isEmpty())
        .map(decision -> admission(freeNow, decision));
  }

  // Why no worker can run the decision's encode, whatever slots are free.
  private Optional<CreateStreamSessionRejection> encodeRejection(
      TranscodeDecisionRequest request, TranscodeDecision decision) {
    if (decision.encodeCodecProfile().isEmpty()) {
      return Optional.empty();
    }

    // Workers encode at the frame-rate rule's output frame rate and refuse an encode without one.
    if (decision.outputFrameRate().isEmpty()) {
      return Optional.of(new CreateStreamSessionRejection.FrameRateUnknown());
    }

    // An encode whose ladder has no variant has no picture that the worker encodes.
    var variants = resolveLadder(request, decision).variants();
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
      UUID sessionId, TranscodeDecisionRequest request) {
    var excludedFamilies = new HashSet<>(request.excludedEncodeCodecFamilies());
    var candidate = stoppableEncodeCandidate(request, excludedFamilies);
    if (candidate.isEmpty()) {
      return Optional.empty();
    }

    // When no left variant runs on a worker that encodes the preferred family, the next family's
    // workers may still hold one.
    while (candidate.isPresent()
        && !stopLeftVariantForTheFamilyOf(sessionId, request, candidate.get())) {
      excludedFamilies.add(candidate.get().videoCodecFamily());
      candidate = stoppableEncodeCandidate(request, excludedFamilies);
    }

    // The first decision already refused a copy, so only an encode is decided again. The stopped
    // variant's worker may encode a family in hardware that no free worker encoded before.
    return transcodeDecisionService.decide(
        withEncodersFreeNow(request).toBuilder()
            .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
            .build());
  }

  // Only some workers may write the candidate's 10-bit or HDR output, so a left variant on a worker
  // that writes the codec family in 8-bit SDR also makes room before the family is given up, when
  // the client plays that encode.
  private boolean stopLeftVariantForTheFamilyOf(
      UUID sessionId, TranscodeDecisionRequest request, TranscodeDecision candidate) {
    if (producerLifecycle.stopLeftVariantOfAnotherStreamSession(sessionId, candidate)) {
      return true;
    }

    return !candidate.videoEncodeFormat().equals(EncodeFormat.SDR_8_BIT)
        && candidate
            .encodeCodecProfile()
            .filter(
                profile ->
                    TranscodeDecisionService.acceptsEightBitSdrEncode(
                        request.capabilityDeclaration(), request.source(), profile))
            .isPresent()
        && producerLifecycle.stopLeftVariantOfAnotherStreamSession(
            sessionId, candidate.toBuilder().videoEncodeFormat(EncodeFormat.SDR_8_BIT).build());
  }

  // The request as the workers' encoders with a free slot stand now.
  private TranscodeDecisionRequest withEncodersFreeNow(TranscodeDecisionRequest request) {
    return request.toBuilder()
        .encodableVideoCodecFamilies(transcodeExecutor.encodableVideoCodecFamilies())
        .hardwareEncodableVideoCodecFamilies(
            transcodeExecutor.hardwareEncodableVideoCodecFamilies())
        .videoEncoders(encodableVideoEncoders(request.softwareEncodeCodecFamily()))
        .build();
  }

  // A software encode after a hardware encoder failure runs on the software encoders, which may
  // write less than the hardware encoders of the same codec family (ADR 0044).
  private List<WorkerVerifiedEncoder> encodableVideoEncoders(
      Optional<String> softwareEncodeCodecFamily) {
    if (softwareEncodeCodecFamily.isPresent()) {
      return transcodeExecutor.encodableSoftwareVideoEncoders();
    }

    return transcodeExecutor.encodableVideoEncoders();
  }

  private List<WorkerVerifiedEncoder> verifiedVideoEncoders(
      Optional<String> softwareEncodeCodecFamily) {
    if (softwareEncodeCodecFamily.isPresent()) {
      return transcodeExecutor.verifiedSoftwareVideoEncoders();
    }

    return transcodeExecutor.verifiedVideoEncoders();
  }

  private Optional<TranscodeDecision> stoppableEncodeCandidate(
      TranscodeDecisionRequest request, Set<String> excludedFamilies) {
    if (!transcodeExecutor.isHealthy()) {
      return Optional.empty();
    }

    return verifiedEncodeCandidate(
            request.toBuilder().excludedEncodeCodecFamilies(excludedFamilies).build())
        .filter(decision -> encodeRejection(request, decision).isEmpty());
  }

  private CreateStreamSessionRejection unavailableVideoFormat(TranscodeDecisionRequest request) {
    if (!transcodeExecutor.isHealthy()) {
      return refuseForLackOfCapacity(CapacityRefusal.NO_CONNECTED_WORKER);
    }

    // Judged from the stream session's read of the connected workers alone, so a worker that
    // disconnects during the refusal neither hides its busy encoder nor counts as missing one.
    if (verifiedEncodeCandidate(request).isPresent()) {
      return refuseForLackOfCapacity(CapacityRefusal.NO_ENCODER_WITH_FREE_SLOT);
    }

    encodeCandidateMissingAnEncoder(request).ifPresent(this::countMissingEncoderSession);
    return new CreateStreamSessionRejection.NoEligibleVideoFormat();
  }

  // The encode that connected workers would run if each had an encoder for every codec family
  // that the server encodes. No worker has an encoder for its codec family, or the session would
  // have had a verified encode candidate.
  private Optional<TranscodeDecision> encodeCandidateMissingAnEncoder(
      TranscodeDecisionRequest request) {
    return encodeCandidate(request, Set.copyOf(EncodeCodecProfile.codecFamilyNames()))
        .filter(decision -> encodeRejection(request, decision).isEmpty());
  }

  private void countMissingEncoderSession(TranscodeDecision decision) {
    Counter.builder(MISSING_ENCODER_SESSIONS_METRIC)
        .description(
            "Stream sessions that need an encode in a codec family for which no connected worker"
                + " has a worker-verified encoder")
        .tag("codec_family", decision.videoCodecFamily())
        .register(meterRegistry)
        .increment();
  }

  // The encode that a connected worker's verified encoder could run once it had a free slot. Busy
  // workers' hardware encoders and encode features count too, so the encode is the one that the
  // server would decide once those workers had a free slot.
  private Optional<TranscodeDecision> verifiedEncodeCandidate(TranscodeDecisionRequest request) {
    return encodeCandidate(
        request.toBuilder()
            .hardwareEncodableVideoCodecFamilies(
                transcodeExecutor.verifiedHardwareVideoCodecFamilies())
            .videoEncoders(request.verifiedVideoEncoders())
            .build(),
        request.verifiedVideoCodecFamilies());
  }

  // The encode that workers could run if they encoded exactly these codec families. It reads no
  // worker state, so a caller decides from the workers that it has read.
  private Optional<TranscodeDecision> encodeCandidate(
      TranscodeDecisionRequest request, Set<String> encodableVideoCodecFamilies) {
    return transcodeDecisionService.decide(
        request.toBuilder()
            .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
            .encodableVideoCodecFamilies(encodableVideoCodecFamilies)
            .build());
  }

  private static Set<String> codecFamiliesOf(List<WorkerVerifiedCodecFamilies> workers) {
    return workers.stream()
        .flatMap(worker -> worker.codecFamilies().stream())
        .collect(Collectors.toUnmodifiableSet());
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
    var session =
        runtimeRegistry
            .findById(request.streamSessionId())
            .filter(found -> found.getDelivery() == request.delivery());
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

  @Override
  public boolean destroySessionIfLastAccessedBefore(UUID sessionId, Instant cutoff) {
    if (!producerLifecycle.removeSessionIfLastAccessedBefore(sessionId, cutoff)) {
      return false;
    }

    cleanUpDestroyed(sessionId);
    return true;
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

  // Every encode runs as a variant of the ladder; a copy keeps the source and the session's audio
  // and has no ladder.
  private QualityLadder resolveLadder(
      TranscodeDecisionRequest decisionRequest, TranscodeDecision decision) {
    return decision
        .encodeCodecProfile()
        .map(codecProfile -> encodedLadder(decisionRequest, decision, codecProfile))
        .orElseGet(() -> new QualityLadder(List.of(), decision.audioDecision()));
  }

  private QualityLadder encodedLadder(
      TranscodeDecisionRequest decisionRequest,
      TranscodeDecision decision,
      EncodeCodecProfile codecProfile) {
    var declaration = decisionRequest.capabilityDeclaration();
    var request =
        QualityLadderRequest.builder()
            .probe(decisionRequest.source())
            .viewerLimits(decisionRequest.viewerLimits().forEncodedLadder())
            .capabilityDeclaration(declaration)
            .codecProfile(codecProfile)
            .encodeFormat(decision.videoEncodeFormat())
            // The stream session refuses an encode without an output frame rate before it sizes a
            // variant.
            .frameRate(decision.outputFrameRate().orElseThrow())
            .audioSteps(
                AudioSteps.below(
                    decision.audioDecision(),
                    AudioTrackChannels.of(decisionRequest.source()),
                    declaration))
            .build();
    var ladder = qualityLadderService.generateVariants(request);
    return new QualityLadder(
        MultivariantPlaylistOrder.of(ladder.variants(), ladder.audio().bitrate()), ladder.audio());
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
