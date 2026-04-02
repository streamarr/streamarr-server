package com.streamarr.server.services.watchprogress;

import com.streamarr.server.config.WatchProgressProperties;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.streaming.SaveProgressCommand;
import com.streamarr.server.repositories.streaming.WatchProgressRepository;
import com.streamarr.server.services.streaming.StreamSessionRepository;
import com.streamarr.server.services.watchprogress.events.WatchProgressChangedEvent;
import com.streamarr.server.services.watchprogress.events.WatchStatusChangedEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WatchProgressService {

  private final StreamSessionRepository sessionRepository;
  private final WatchProgressRepository watchProgressRepository;
  private final MediaFileRepository mediaFileRepository;
  private final EpisodeRepository episodeRepository;
  private final SeasonRepository seasonRepository;
  private final WatchProgressProperties properties;
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
        watchProgressRepository.upsertProgress(
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
        WatchProgressChangedEvent.builder()
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
      watchProgressRepository.deleteIfNotWatched(userId, mediaFileId);
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

    var written = watchProgressRepository.upsertProgress(command);

    if (!written) {
      log.debug(
          "Ignored timeline update for media file {} — already marked as watched", mediaFileId);
      return;
    }

    eventPublisher.publishEvent(
        WatchProgressChangedEvent.builder()
            .userId(userId)
            .mediaFileId(mediaFileId)
            .positionSeconds(command.positionSeconds())
            .percentComplete(command.percentComplete())
            .state(PlaybackState.STOPPED)
            .build());

    if (command instanceof SaveProgressCommand.MarkWatched) {
      eventPublisher.publishEvent(new WatchStatusChangedEvent(userId, mediaFileId));
    }
  }

  public void resetProgress(UUID userId, UUID collectableId) {
    var mediaFileIds = resolveAllMediaFileIds(collectableId);

    if (!mediaFileIds.isEmpty()) {
      watchProgressRepository.deleteByUserIdAndMediaFileIdIn(userId, mediaFileIds);
    }
  }

  private List<UUID> resolveAllMediaFileIds(UUID collectableId) {
    var directFiles = mediaFileRepository.findByMediaId(collectableId);
    if (!directFiles.isEmpty()) {
      return directFiles.stream().map(MediaFile::getId).toList();
    }

    var episodes = episodeRepository.findBySeasonId(collectableId);
    if (!episodes.isEmpty()) {
      var episodeIds = episodes.stream().map(Episode::getId).toList();
      return mediaFileRepository.findByMediaIdIn(episodeIds).stream()
          .map(MediaFile::getId)
          .toList();
    }

    var seasons = seasonRepository.findBySeriesId(collectableId);
    if (!seasons.isEmpty()) {
      var seasonIds = seasons.stream().map(Season::getId).toList();
      var seriesEpisodes = episodeRepository.findBySeasonIdIn(seasonIds);
      var episodeIds = seriesEpisodes.stream().map(Episode::getId).toList();
      return mediaFileRepository.findByMediaIdIn(episodeIds).stream()
          .map(MediaFile::getId)
          .toList();
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
