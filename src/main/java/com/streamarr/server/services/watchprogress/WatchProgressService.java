package com.streamarr.server.services.watchprogress;

import com.streamarr.server.config.WatchProgressProperties;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.WatchProgress;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.streaming.WatchProgressRepository;
import com.streamarr.server.services.streaming.StreamSessionRepository;
import com.streamarr.server.services.watchprogress.events.MediaWatchedEvent;
import com.streamarr.server.services.watchprogress.events.PlaybackStoppedEvent;
import com.streamarr.server.services.watchprogress.events.TimelineReportedEvent;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

@Slf4j
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
    var remainingSeconds = durationSeconds - positionSeconds;
    var mediaFileId = session.getMediaFileId();

    if (state == PlaybackState.STOPPED) {
      eventPublisher.publishEvent(
          new PlaybackStoppedEvent(
              userId, sessionId, mediaFileId, positionSeconds, percentComplete));
    }

    if (state == PlaybackState.STOPPED && percentComplete < properties.minResumePercent()) {
      watchProgressRepository.deleteIfNotWatched(userId, mediaFileId);
      return;
    }

    var watched =
        state == PlaybackState.STOPPED
            && (percentComplete >= properties.maxResumePercent()
                || remainingSeconds <= properties.maxRemainingSeconds());

    var lastPlayedAt = watched ? Instant.now() : null;
    var effectivePosition = watched ? 0 : positionSeconds;

    var written =
        watchProgressRepository.upsertProgress(
            userId, mediaFileId, effectivePosition, percentComplete, durationSeconds, lastPlayedAt);

    if (!written) {
      log.debug("Ignored timeline update for media file {} — already marked as watched", mediaFileId);
      return;
    }

    eventPublisher.publishEvent(
        new TimelineReportedEvent(userId, mediaFileId, effectivePosition, percentComplete, state));

    if (watched) {
      eventPublisher.publishEvent(new MediaWatchedEvent(userId, mediaFileId));
    }
  }

  public Optional<WatchProgress> getProgress(UUID userId, UUID mediaFileId) {
    return watchProgressRepository.findByUserIdAndMediaFileId(userId, mediaFileId);
  }

  public Map<UUID, WatchProgress> getProgressForMediaFiles(
      UUID userId, Collection<UUID> mediaFileIds) {
    return watchProgressRepository.findByUserIdAndMediaFileIdIn(userId, mediaFileIds).stream()
        .collect(Collectors.toMap(WatchProgress::getMediaFileId, wp -> wp));
  }

  public Map<UUID, WatchStatus> getWatchStatusForDirectMedia(
      UUID userId, Collection<UUID> collectableIds) {
    var mediaFiles = mediaFileRepository.findByMediaIdIn(collectableIds);
    if (mediaFiles.isEmpty()) {
      return Map.of();
    }

    var mediaFilesByCollectable =
        mediaFiles.stream().collect(Collectors.groupingBy(MediaFile::getMediaId));
    var allMediaFileIds = mediaFiles.stream().map(MediaFile::getId).toList();
    var progressMap = getProgressForMediaFiles(userId, allMediaFileIds);

    return deriveWatchStatusMap(mediaFilesByCollectable, progressMap);
  }

  public Map<UUID, WatchStatus> getWatchStatusForSeasons(UUID userId, Collection<UUID> seasonIds) {
    var episodes = episodeRepository.findBySeasonIdIn(seasonIds);
    if (episodes.isEmpty()) {
      return Map.of();
    }

    var episodeIdsBySeasonId =
        episodes.stream()
            .collect(Collectors.groupingBy(ep -> ep.getSeason().getId(), Collectors.toList()));
    var allEpisodeIds = episodes.stream().map(Episode::getId).toList();
    var mediaFiles = mediaFileRepository.findByMediaIdIn(allEpisodeIds);
    if (mediaFiles.isEmpty()) {
      return Map.of();
    }

    var mediaFilesByEpisodeId =
        mediaFiles.stream().collect(Collectors.groupingBy(MediaFile::getMediaId));
    var allMediaFileIds = mediaFiles.stream().map(MediaFile::getId).toList();
    var progressMap = getProgressForMediaFiles(userId, allMediaFileIds);

    var result = new HashMap<UUID, WatchStatus>();
    for (var entry : episodeIdsBySeasonId.entrySet()) {
      var seasonMediaFileIds =
          entry.getValue().stream()
              .flatMap(ep -> mediaFilesByEpisodeId.getOrDefault(ep.getId(), List.of()).stream())
              .map(MediaFile::getId)
              .toList();

      result.put(entry.getKey(), deriveWatchStatusFromFileIds(seasonMediaFileIds, progressMap));
    }
    return result;
  }

  public Map<UUID, WatchStatus> getWatchStatusForSeries(UUID userId, Collection<UUID> seriesIds) {
    var seasons = seasonRepository.findBySeriesIdIn(seriesIds);
    if (seasons.isEmpty()) {
      return Map.of();
    }

    var seasonIdsBySeriesId =
        seasons.stream()
            .collect(Collectors.groupingBy(s -> s.getSeries().getId(), Collectors.toList()));
    var allSeasonIds = seasons.stream().map(Season::getId).toList();
    var episodes = episodeRepository.findBySeasonIdIn(allSeasonIds);
    if (episodes.isEmpty()) {
      return Map.of();
    }

    var episodesBySeasonId =
        episodes.stream()
            .collect(Collectors.groupingBy(ep -> ep.getSeason().getId(), Collectors.toList()));
    var allEpisodeIds = episodes.stream().map(Episode::getId).toList();
    var mediaFiles = mediaFileRepository.findByMediaIdIn(allEpisodeIds);
    if (mediaFiles.isEmpty()) {
      return Map.of();
    }

    var mediaFilesByEpisodeId =
        mediaFiles.stream().collect(Collectors.groupingBy(MediaFile::getMediaId));
    var allMediaFileIds = mediaFiles.stream().map(MediaFile::getId).toList();
    var progressMap = getProgressForMediaFiles(userId, allMediaFileIds);

    var result = new HashMap<UUID, WatchStatus>();
    for (var seriesEntry : seasonIdsBySeriesId.entrySet()) {
      var seriesMediaFileIds =
          collectMediaFileIds(seriesEntry.getValue(), episodesBySeasonId, mediaFilesByEpisodeId);

      result.put(
          seriesEntry.getKey(), deriveWatchStatusFromFileIds(seriesMediaFileIds, progressMap));
    }
    return result;
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

  private static List<UUID> collectMediaFileIds(
      List<Season> seasons,
      Map<UUID, List<Episode>> episodesBySeasonId,
      Map<UUID, List<MediaFile>> mediaFilesByEpisodeId) {
    return seasons.stream()
        .flatMap(season -> episodesBySeasonId.getOrDefault(season.getId(), List.of()).stream())
        .flatMap(ep -> mediaFilesByEpisodeId.getOrDefault(ep.getId(), List.of()).stream())
        .map(MediaFile::getId)
        .toList();
  }

  private static Map<UUID, WatchStatus> deriveWatchStatusMap(
      Map<UUID, List<MediaFile>> mediaFilesByCollectable, Map<UUID, WatchProgress> progressMap) {
    var result = new HashMap<UUID, WatchStatus>();
    for (var entry : mediaFilesByCollectable.entrySet()) {
      var fileIds = entry.getValue().stream().map(MediaFile::getId).toList();
      result.put(entry.getKey(), deriveWatchStatusFromFileIds(fileIds, progressMap));
    }
    return result;
  }

  private static WatchStatus deriveWatchStatusFromFileIds(
      List<UUID> mediaFileIds, Map<UUID, WatchProgress> progressMap) {
    var watchedCount = 0;
    var inProgressCount = 0;
    for (var mediaFileId : mediaFileIds) {
      var wp = progressMap.get(mediaFileId);
      if (wp == null) {
        continue;
      }
      if (wp.isPlayed()) {
        watchedCount++;
      } else if (wp.getPositionSeconds() > 0) {
        inProgressCount++;
      }
    }
    return deriveWatchStatus(mediaFileIds.size(), watchedCount, inProgressCount);
  }

  private static WatchStatus deriveWatchStatus(
      int totalItems, int watchedCount, int inProgressCount) {
    if (totalItems > 0 && watchedCount == totalItems) {
      return WatchStatus.WATCHED;
    }

    if (watchedCount > 0 || inProgressCount > 0) {
      return WatchStatus.IN_PROGRESS;
    }

    return WatchStatus.UNWATCHED;
  }
}
