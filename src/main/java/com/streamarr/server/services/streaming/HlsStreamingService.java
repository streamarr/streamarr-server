package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.MaxConcurrentTranscodesException;
import com.streamarr.server.exceptions.MediaFileNotFoundException;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class HlsStreamingService implements StreamingService {

  private final MediaFileRepository mediaFileRepository;
  private final TranscodeExecutor transcodeExecutor;
  private final SegmentStore segmentStore;
  private final FfprobeService ffprobeService;
  private final TranscodeDecisionService transcodeDecisionService;
  private final QualityLadderService qualityLadderService;
  private final StreamingProperties properties;
  private final StreamSessionRepository sessionRepository;

  @Override
  public StreamSession createSession(UUID mediaFileId, StreamingOptions options) {
    var mediaFile =
        mediaFileRepository
            .findById(mediaFileId)
            .orElseThrow(() -> new MediaFileNotFoundException(mediaFileId));

    var probe = ffprobeService.probe(Path.of(mediaFile.getFilepath()));
    var decision = transcodeDecisionService.decide(probe, options);
    var variants = resolveVariants(probe, options, decision);
    variants = enforceCapacityLimits(decision.transcodeMode(), variants);

    var sessionId = UUID.randomUUID();
    var now = Instant.now();

    var session =
        StreamSession.builder()
            .sessionId(sessionId)
            .mediaFileId(mediaFileId)
            .sourcePath(Path.of(mediaFile.getFilepath()))
            .mediaProbe(probe)
            .transcodeDecision(decision)
            .options(options)
            .variants(variants)
            .seekPosition(0)
            .createdAt(now)
            .lastAccessedAt(now)
            .build();

    sessionRepository.save(session);
    startTranscodes(session, 0);
    sessionRepository.save(session);
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
    var session = sessionRepository.findById(sessionId);
    session.ifPresent(
        s -> {
          s.setLastAccessedAt(Instant.now());
          sessionRepository.save(s);
        });
    return session;
  }

  @Override
  public StreamSession seekSession(UUID sessionId, int positionSeconds) {
    var session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new SessionNotFoundException(sessionId));

    transcodeExecutor.stop(sessionId);
    segmentStore.deleteSession(sessionId);
    session.setSeekPosition(positionSeconds);
    startTranscodes(session, positionSeconds);

    session.setLastAccessedAt(Instant.now());
    sessionRepository.save(session);

    log.info("Seeked session {} to position {}s", sessionId, positionSeconds);
    return session;
  }

  @Override
  public void destroySession(UUID sessionId) {
    sessionRepository
        .removeById(sessionId)
        .ifPresent(
            session -> {
              transcodeExecutor.stop(sessionId);
              segmentStore.deleteSession(sessionId);
              log.info("Destroyed streaming session {}", sessionId);
            });
  }

  @Override
  public Collection<StreamSession> getAllSessions() {
    return sessionRepository.findAll();
  }

  @Override
  public int getActiveSessionCount() {
    return sessionRepository.count();
  }

  private List<QualityVariant> resolveVariants(
      MediaProbe probe, StreamingOptions options, TranscodeDecision decision) {
    if (!isAutoQuality(options) || decision.transcodeMode() != TranscodeMode.FULL_TRANSCODE) {
      return Collections.emptyList();
    }
    return qualityLadderService.generateVariants(probe, options);
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

  private void startTranscodes(StreamSession session, int seekPosition) {
    if (!session.getVariants().isEmpty()) {
      startVariantTranscodes(session, session.getVariants(), seekPosition);
      return;
    }
    startSingleTranscode(session, seekPosition);
  }

  private void startVariantTranscodes(
      StreamSession session, List<QualityVariant> variants, int seekPosition) {
    for (var variant : variants) {
      var request =
          TranscodeRequest.builder()
              .sessionId(session.getSessionId())
              .sourcePath(session.getSourcePath())
              .seekPosition(seekPosition)
              .segmentDuration(properties.segmentDurationSeconds())
              .framerate(session.getMediaProbe().framerate())
              .transcodeDecision(session.getTranscodeDecision())
              .width(variant.width())
              .height(variant.height())
              .bitrate(variant.videoBitrate())
              .variantLabel(variant.label())
              .build();
      var handle = transcodeExecutor.start(request);
      session.setVariantHandle(variant.label(), handle);
    }
  }

  private void startSingleTranscode(StreamSession session, int seekPosition) {
    var probe = session.getMediaProbe();
    var request =
        TranscodeRequest.builder()
            .sessionId(session.getSessionId())
            .sourcePath(session.getSourcePath())
            .seekPosition(seekPosition)
            .segmentDuration(properties.segmentDurationSeconds())
            .framerate(probe.framerate())
            .transcodeDecision(session.getTranscodeDecision())
            .width(probe.width())
            .height(probe.height())
            .bitrate(probe.bitrate())
            .variantLabel(StreamSession.defaultVariant())
            .build();
    var handle = transcodeExecutor.start(request);
    session.setHandle(handle);
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

  private boolean isAutoQuality(StreamingOptions options) {
    return options.quality() == null || options.quality() == VideoQuality.AUTO;
  }

  private boolean requiresTranscode(TranscodeMode mode) {
    return mode == TranscodeMode.PARTIAL_TRANSCODE || mode == TranscodeMode.FULL_TRANSCODE;
  }

  private int availableTranscodeSlots() {
    var activeTranscodes =
        sessionRepository.findAll().stream()
            .filter(s -> requiresTranscode(s.getTranscodeDecision().transcodeMode()))
            .mapToInt(s -> Math.max(1, s.getVariants().size()))
            .sum();
    return properties.maxConcurrentTranscodes() - activeTranscodes;
  }

  private void enforceTranscodeLimit() {
    if (availableTranscodeSlots() <= 0) {
      throw new MaxConcurrentTranscodesException(properties.maxConcurrentTranscodes());
    }
  }
}
