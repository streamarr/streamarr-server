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
import com.streamarr.server.services.watchprogress.events.WatchStatusChangedEvent;
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
      handleStoppedPlayback(userId, mediaFileId, positionSeconds, percentComplete, durationSeconds);
      return;
    }

    var written =
        sessionProgressRepository.upsertProgress(
            SaveProgressCommand.UpdateProgress.builder()
                .userId(userId)
                .mediaFileId(mediaFileId)
                .positionSeconds(positionSeconds)
                .percentComplete(percentComplete)
                .durationSeconds(durationSeconds)
                .build());

    if (!written) {
      log.debug(
          "Ignored timeline update for media file {} — already marked as watched", mediaFileId);
      return;
    }

    eventPublisher.publishEvent(
        SessionProgressChangedEvent.builder()
            .userId(userId)
            .mediaFileId(mediaFileId)
            .positionSeconds(positionSeconds)
            .percentComplete(percentComplete)
            .state(state)
            .build());
  }

  private void handleStoppedPlayback(
      UUID userId,
      UUID mediaFileId,
      int positionSeconds,
      double percentComplete,
      int durationSeconds) {
    var remainingSeconds = durationSeconds - positionSeconds;
    var decision = evaluateStopDecision(percentComplete, remainingSeconds, durationSeconds);

    if (decision == StopDecision.DISCARD) {
      sessionProgressRepository.deleteIfNotWatched(userId, mediaFileId);
      return;
    }

    SaveProgressCommand command =
        switch (decision) {
          case MARK_WATCHED ->
              SaveProgressCommand.MarkWatched.builder()
                  .userId(userId)
                  .mediaFileId(mediaFileId)
                  .positionSeconds(0)
                  .percentComplete(100.0)
                  .durationSeconds(durationSeconds)
                  .watchedAt(Instant.now())
                  .build();
          case PERSIST ->
              SaveProgressCommand.UpdateProgress.builder()
                  .userId(userId)
                  .mediaFileId(mediaFileId)
                  .positionSeconds(positionSeconds)
                  .percentComplete(percentComplete)
                  .durationSeconds(durationSeconds)
                  .build();
          case DISCARD -> throw new AssertionError("unreachable: DISCARD handled above");
        };

    var written = sessionProgressRepository.upsertProgress(command);

    if (!written) {
      log.debug(
          "Ignored timeline update for media file {} — already marked as watched", mediaFileId);
      return;
    }

    eventPublisher.publishEvent(
        SessionProgressChangedEvent.builder()
            .userId(userId)
            .mediaFileId(mediaFileId)
            .positionSeconds(command.positionSeconds())
            .percentComplete(command.percentComplete())
            .state(PlaybackState.STOPPED)
            .build());

    switch (command) {
      case SaveProgressCommand.MarkWatched _ ->
          eventPublisher.publishEvent(new WatchStatusChangedEvent(userId, mediaFileId));
      case SaveProgressCommand.UpdateProgress _ -> {}
    }
  }

  public void resetProgress(UUID userId, UUID collectableId) {
    var mediaFileIds = resolveAllMediaFileIds(collectableId);

    if (!mediaFileIds.isEmpty()) {
      sessionProgressRepository.deleteByUserIdAndMediaFileIdIn(userId, mediaFileIds);
    }
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
