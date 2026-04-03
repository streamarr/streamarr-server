package com.streamarr.server.services.watchprogress;

import com.streamarr.server.config.SessionProgressProperties;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.streaming.SaveProgressCommand;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.services.streaming.StreamSessionRepository;
import com.streamarr.server.services.watchprogress.events.SessionProgressChangedEvent;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionProgressService {

  private final StreamSessionRepository sessionRepository;
  private final SessionProgressRepository sessionProgressRepository;
  private final MediaFileRepository mediaFileRepository;
  private final EpisodeRepository episodeRepository;
  private final SeasonRepository seasonRepository;
  private final SessionProgressProperties properties;
  private final WatchStatusService watchStatusService;
  private final ApplicationEventPublisher eventPublisher;

  public void reportTimeline(
      UUID userId, UUID sessionId, int positionSeconds, PlaybackState state) {
    if (positionSeconds < 0) {
      return;
    }

    var session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new SessionNotFoundException(sessionId));

    session.updatePlaybackState(positionSeconds, state);

    var durationSeconds = (int) session.getMediaProbe().duration().toSeconds();
    if (durationSeconds <= 0) {
      return;
    }

    var percentComplete = Math.min(positionSeconds * 100.0 / durationSeconds, 100.0);
    var mediaFileId = session.getMediaFileId();

    if (state == PlaybackState.STOPPED) {
      handleStoppedPlayback(
          userId, sessionId, mediaFileId, positionSeconds, percentComplete, durationSeconds);
      return;
    }

    sessionProgressRepository.upsertProgress(
        SaveProgressCommand.builder()
            .sessionId(sessionId)
            .userId(userId)
            .mediaFileId(mediaFileId)
            .positionSeconds(positionSeconds)
            .percentComplete(percentComplete)
            .durationSeconds(durationSeconds)
            .build());

    eventPublisher.publishEvent(
        SessionProgressChangedEvent.builder()
            .sessionId(sessionId)
            .userId(userId)
            .mediaFileId(mediaFileId)
            .positionSeconds(positionSeconds)
            .percentComplete(percentComplete)
            .state(state)
            .build());
  }

  private void handleStoppedPlayback(
      UUID userId,
      UUID sessionId,
      UUID mediaFileId,
      int positionSeconds,
      double percentComplete,
      int durationSeconds) {
    var remainingSeconds = durationSeconds - positionSeconds;
    var decision = evaluateStopDecision(percentComplete, remainingSeconds, durationSeconds);

    if (decision == StopDecision.DISCARD) {
      sessionProgressRepository.deleteBySessionId(sessionId);
      return;
    }

    if (decision == StopDecision.MARK_WATCHED) {
      sessionProgressRepository.deleteBySessionId(sessionId);

      var collectableId = resolveCollectableId(mediaFileId);
      watchStatusService.markWatched(userId, collectableId, Instant.now(), durationSeconds);

      eventPublisher.publishEvent(
          SessionProgressChangedEvent.builder()
              .sessionId(sessionId)
              .userId(userId)
              .mediaFileId(mediaFileId)
              .collectableId(collectableId)
              .positionSeconds(0)
              .percentComplete(100.0)
              .state(PlaybackState.STOPPED)
              .build());
      return;
    }

    // PERSIST — save position for resume
    sessionProgressRepository.upsertProgress(
        SaveProgressCommand.builder()
            .sessionId(sessionId)
            .userId(userId)
            .mediaFileId(mediaFileId)
            .positionSeconds(positionSeconds)
            .percentComplete(percentComplete)
            .durationSeconds(durationSeconds)
            .build());

    eventPublisher.publishEvent(
        SessionProgressChangedEvent.builder()
            .sessionId(sessionId)
            .userId(userId)
            .mediaFileId(mediaFileId)
            .positionSeconds(positionSeconds)
            .percentComplete(percentComplete)
            .state(PlaybackState.STOPPED)
            .build());
  }

  public void resetProgress(UUID userId, UUID collectableId) {
    var mediaFileIds = resolveAllMediaFileIds(collectableId);

    if (!mediaFileIds.isEmpty()) {
      sessionProgressRepository.deleteByUserIdAndMediaFileIdIn(userId, mediaFileIds);
    }
  }

  private UUID resolveCollectableId(UUID mediaFileId) {
    return mediaFileRepository
        .findById(mediaFileId)
        .orElseThrow(() -> new IllegalStateException("MediaFile not found for id: " + mediaFileId))
        .getMediaId();
  }

  private List<UUID> resolveAllMediaFileIds(UUID collectableId) {
    var directFileIds = mediaFileRepository.findMediaFileIdsByMediaIds(List.of(collectableId));
    if (!directFileIds.isEmpty()) {
      return directFileIds;
    }

    var episodeIdsBySeasonId = episodeRepository.findEpisodeIdsBySeasonIds(List.of(collectableId));
    if (!episodeIdsBySeasonId.isEmpty()) {
      var episodeIds = episodeIdsBySeasonId.values().stream().flatMap(Collection::stream).toList();
      return mediaFileRepository.findMediaFileIdsByMediaIds(episodeIds);
    }

    var seasonIdsBySeriesId = seasonRepository.findSeasonIdsBySeriesIds(List.of(collectableId));
    if (!seasonIdsBySeriesId.isEmpty()) {
      var allSeasonIds = seasonIdsBySeriesId.values().stream().flatMap(Collection::stream).toList();
      var episodeIdsBySeasonId2 = episodeRepository.findEpisodeIdsBySeasonIds(allSeasonIds);
      var episodeIds = episodeIdsBySeasonId2.values().stream().flatMap(Collection::stream).toList();
      return mediaFileRepository.findMediaFileIdsByMediaIds(episodeIds);
    }

    return List.of();
  }

  private enum StopDecision {
    DISCARD,
    MARK_WATCHED,
    PERSIST
  }

  private StopDecision evaluateStopDecision(
      double percentComplete, int remainingSeconds, int durationSeconds) {
    if (isBelowResumeThreshold(percentComplete)) {
      return StopDecision.DISCARD;
    }

    if (hasReachedWatchedThreshold(percentComplete, remainingSeconds, durationSeconds)) {
      return StopDecision.MARK_WATCHED;
    }

    return StopDecision.PERSIST;
  }

  private boolean isBelowResumeThreshold(double percentComplete) {
    return percentComplete < properties.minResumePercent();
  }

  private boolean hasReachedWatchedThreshold(
      double percentComplete, int remainingSeconds, int durationSeconds) {
    return percentComplete >= properties.maxResumePercent()
        || (durationSeconds > properties.maxRemainingSeconds()
            && remainingSeconds <= properties.maxRemainingSeconds());
  }
}
