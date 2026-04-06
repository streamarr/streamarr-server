package com.streamarr.server.services.watchprogress;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.domain.streaming.WatchHistory;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import com.streamarr.server.services.watchprogress.events.WatchStatusChangedEvent;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WatchStatusService {

  private final SessionProgressRepository sessionProgressRepository;
  private final WatchHistoryRepository watchHistoryRepository;
  private final MediaFileRepository mediaFileRepository;
  private final EpisodeRepository episodeRepository;
  private final SeasonRepository seasonRepository;
  private final ApplicationEventPublisher eventPublisher;

  public Map<UUID, SessionProgress> getProgressForMediaFiles(
      UUID userId, Collection<UUID> mediaFileIds) {
    return sessionProgressRepository.findByUserIdAndMediaFileIdIn(userId, mediaFileIds).stream()
        .collect(
            Collectors.toMap(
                SessionProgress::getMediaFileId,
                sp -> sp,
                (a, b) ->
                    a.getLastModifiedOn().isAfter(b.getLastModifiedOn()) ? a : b));
  }

  public void markWatched(UUID userId, UUID collectableId, Instant watchedAt, int durationSeconds) {
    var leafIds = resolveLeafCollectableIds(collectableId);
    watchHistoryRepository.batchInsert(userId, leafIds, watchedAt, durationSeconds);

    eventPublisher.publishEvent(new WatchStatusChangedEvent(userId, collectableId));
  }

  public void markUnwatched(UUID userId, UUID collectableId) {
    var leafIds = resolveLeafCollectableIds(collectableId);
    watchHistoryRepository.dismissAll(userId, leafIds);

    var mediaFileIds = mediaFileRepository.findMediaFileIdsByMediaIds(leafIds);
    if (!mediaFileIds.isEmpty()) {
      sessionProgressRepository.deleteByUserIdAndMediaFileIds(userId, mediaFileIds);
    }

    eventPublisher.publishEvent(new WatchStatusChangedEvent(userId, collectableId));
  }

  private List<UUID> resolveLeafCollectableIds(UUID collectableId) {
    var episodeIdsBySeasonId = episodeRepository.findEpisodeIdsBySeasonIds(List.of(collectableId));
    if (!episodeIdsBySeasonId.isEmpty()) {
      return episodeIdsBySeasonId.values().stream().flatMap(Collection::stream).toList();
    }

    var seasonIdsBySeriesId = seasonRepository.findSeasonIdsBySeriesIds(List.of(collectableId));
    if (!seasonIdsBySeriesId.isEmpty()) {
      var allSeasonIds = seasonIdsBySeriesId.values().stream().flatMap(Collection::stream).toList();
      var episodeIdsBySeason = episodeRepository.findEpisodeIdsBySeasonIds(allSeasonIds);
      return episodeIdsBySeason.values().stream().flatMap(Collection::stream).toList();
    }

    return List.of(collectableId);
  }

  public Map<UUID, WatchStatus> getWatchStatusForDirectMedia(
      UUID userId, Collection<UUID> collectableIds) {
    var watchedIds = findWatchedCollectableIds(userId, collectableIds);

    var mediaFiles = mediaFileRepository.findByMediaIdIn(collectableIds);
    var mediaFilesByCollectable =
        mediaFiles.stream().collect(Collectors.groupingBy(MediaFile::getMediaId));
    var allMediaFileIds = mediaFiles.stream().map(MediaFile::getId).toList();
    var progressMap = getProgressForMediaFiles(userId, allMediaFileIds);

    var result = new HashMap<UUID, WatchStatus>();
    for (var collectableId : collectableIds) {
      if (watchedIds.contains(collectableId)) {
        result.put(collectableId, WatchStatus.WATCHED);
        continue;
      }
      var fileIds =
          mediaFilesByCollectable.getOrDefault(collectableId, List.of()).stream()
              .map(MediaFile::getId)
              .toList();
      result.put(collectableId, deriveProgressStatus(fileIds, progressMap));
    }
    return result;
  }

  public Map<UUID, WatchStatus> getWatchStatusForSeasons(UUID userId, Collection<UUID> seasonIds) {
    var episodeIdsBySeasonId = episodeRepository.findEpisodeIdsBySeasonIds(seasonIds);
    if (episodeIdsBySeasonId.isEmpty()) {
      return Map.of();
    }

    var allEpisodeIds = episodeIdsBySeasonId.values().stream().flatMap(Collection::stream).toList();
    var watchedEpisodeIds = findWatchedCollectableIds(userId, allEpisodeIds);

    var mediaFiles = mediaFileRepository.findByMediaIdIn(allEpisodeIds);
    var mediaFilesByEpisodeId =
        mediaFiles.stream().collect(Collectors.groupingBy(MediaFile::getMediaId));
    var allMediaFileIds = mediaFiles.stream().map(MediaFile::getId).toList();
    var progressMap = getProgressForMediaFiles(userId, allMediaFileIds);

    var result = new HashMap<UUID, WatchStatus>();
    for (var entry : episodeIdsBySeasonId.entrySet()) {
      var episodeIds = entry.getValue();
      var seasonMediaFileIds =
          episodeIds.stream()
              .flatMap(epId -> mediaFilesByEpisodeId.getOrDefault(epId, List.of()).stream())
              .map(MediaFile::getId)
              .toList();

      result.put(
          entry.getKey(),
          deriveAggregateStatus(episodeIds, watchedEpisodeIds, seasonMediaFileIds, progressMap));
    }

    return result;
  }

  public Map<UUID, WatchStatus> getWatchStatusForSeries(UUID userId, Collection<UUID> seriesIds) {
    var seasonIdsBySeriesId = seasonRepository.findSeasonIdsBySeriesIds(seriesIds);
    if (seasonIdsBySeriesId.isEmpty()) {
      return Map.of();
    }

    var allSeasonIds = seasonIdsBySeriesId.values().stream().flatMap(Collection::stream).toList();
    var episodeIdsBySeasonId = episodeRepository.findEpisodeIdsBySeasonIds(allSeasonIds);
    if (episodeIdsBySeasonId.isEmpty()) {
      return Map.of();
    }

    var allEpisodeIds = episodeIdsBySeasonId.values().stream().flatMap(Collection::stream).toList();
    var watchedEpisodeIds = findWatchedCollectableIds(userId, allEpisodeIds);

    var mediaFiles = mediaFileRepository.findByMediaIdIn(allEpisodeIds);
    var mediaFilesByEpisodeId =
        mediaFiles.stream().collect(Collectors.groupingBy(MediaFile::getMediaId));
    var allMediaFileIds = mediaFiles.stream().map(MediaFile::getId).toList();
    var progressMap = getProgressForMediaFiles(userId, allMediaFileIds);

    var result = new HashMap<UUID, WatchStatus>();
    for (var seriesEntry : seasonIdsBySeriesId.entrySet()) {
      var seriesEpisodeIds =
          seriesEntry.getValue().stream()
              .flatMap(seasonId -> episodeIdsBySeasonId.getOrDefault(seasonId, List.of()).stream())
              .toList();
      var seriesMediaFileIds =
          seriesEpisodeIds.stream()
              .flatMap(epId -> mediaFilesByEpisodeId.getOrDefault(epId, List.of()).stream())
              .map(MediaFile::getId)
              .toList();

      result.put(
          seriesEntry.getKey(),
          deriveAggregateStatus(
              seriesEpisodeIds, watchedEpisodeIds, seriesMediaFileIds, progressMap));
    }
    return result;
  }

  private Set<UUID> findWatchedCollectableIds(UUID userId, Collection<UUID> collectableIds) {
    return watchHistoryRepository.findByUserIdAndCollectableIdIn(userId, collectableIds).stream()
        .filter(wh -> wh.getDismissedAt() == null)
        .map(WatchHistory::getCollectableId)
        .collect(Collectors.toSet());
  }

  private static WatchStatus deriveAggregateStatus(
      List<UUID> collectableIds,
      Set<UUID> watchedCollectableIds,
      List<UUID> mediaFileIds,
      Map<UUID, SessionProgress> progressMap) {
    var watchedCount =
        (int) collectableIds.stream().filter(watchedCollectableIds::contains).count();
    var inProgressCount = countInProgress(mediaFileIds, progressMap);
    var totalItems = collectableIds.size();

    if (totalItems > 0 && watchedCount == totalItems) {
      return WatchStatus.WATCHED;
    }

    if (watchedCount > 0 || inProgressCount > 0) {
      return WatchStatus.IN_PROGRESS;
    }

    return WatchStatus.UNWATCHED;
  }

  private static WatchStatus deriveProgressStatus(
      List<UUID> mediaFileIds, Map<UUID, SessionProgress> progressMap) {
    if (countInProgress(mediaFileIds, progressMap) > 0) {
      return WatchStatus.IN_PROGRESS;
    }
    return WatchStatus.UNWATCHED;
  }

  private static int countInProgress(
      List<UUID> mediaFileIds, Map<UUID, SessionProgress> progressMap) {
    var count = 0;
    for (var mediaFileId : mediaFileIds) {
      var sp = progressMap.get(mediaFileId);
      if (sp != null && sp.getPositionSeconds() > 0) {
        count++;
      }
    }
    return count;
  }
}
