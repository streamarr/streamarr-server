package com.streamarr.server.services.watchprogress;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.streaming.CollectableScope;
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
import org.springframework.transaction.annotation.Transactional;

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
                (a, b) -> a.getLastModifiedOn().isAfter(b.getLastModifiedOn()) ? a : b));
  }

  @Transactional
  public void markWatched(UUID userId, UUID collectableId) {
    var scope = resolveCollectableScope(collectableId);
    markWatched(userId, collectableId, scope, Instant.now(), 0);
  }

  @Transactional
  public void markWatched(
      UUID userId,
      UUID collectableId,
      CollectableScope scope,
      Instant watchedAt,
      int durationSeconds) {
    var leafIds = resolveLeafCollectableIds(collectableId, scope);
    watchHistoryRepository.batchInsert(userId, leafIds, watchedAt, durationSeconds);

    eventPublisher.publishEvent(new WatchStatusChangedEvent(userId, collectableId));
  }

  @Transactional
  public void markUnwatched(UUID userId, UUID collectableId) {
    var scope = resolveCollectableScope(collectableId);
    markUnwatched(userId, collectableId, scope);
  }

  @Transactional
  public void markUnwatched(UUID userId, UUID collectableId, CollectableScope scope) {
    var leafIds = resolveLeafCollectableIds(collectableId, scope);
    watchHistoryRepository.dismissAll(userId, leafIds);

    var mediaFileIds = mediaFileRepository.findMediaFileIdsByMediaIds(leafIds);
    if (!mediaFileIds.isEmpty()) {
      sessionProgressRepository.deleteByUserIdAndMediaFileIds(userId, mediaFileIds);
    }

    eventPublisher.publishEvent(new WatchStatusChangedEvent(userId, collectableId));
  }

  private CollectableScope resolveCollectableScope(UUID collectableId) {
    if (!episodeRepository.findEpisodeIdsBySeasonIds(List.of(collectableId)).isEmpty()) {
      return CollectableScope.SEASON;
    }
    if (!seasonRepository.findSeasonIdsBySeriesIds(List.of(collectableId)).isEmpty()) {
      return CollectableScope.SERIES;
    }
    return CollectableScope.DIRECT_MEDIA;
  }

  private List<UUID> resolveLeafCollectableIds(UUID collectableId, CollectableScope scope) {
    return switch (scope) {
      case DIRECT_MEDIA -> List.of(collectableId);
      case SEASON -> flatten(episodeRepository.findEpisodeIdsBySeasonIds(List.of(collectableId)));
      case SERIES -> {
        var seasonIds = flatten(seasonRepository.findSeasonIdsBySeriesIds(List.of(collectableId)));
        yield flatten(episodeRepository.findEpisodeIdsBySeasonIds(seasonIds));
      }
    };
  }

  public Map<UUID, WatchStatus> getWatchStatusForDirectMedia(
      UUID userId, Collection<UUID> collectableIds) {
    var data = fetchCollectableMediaData(userId, collectableIds);

    var result = new HashMap<UUID, WatchStatus>();
    for (var collectableId : collectableIds) {
      if (data.watchedIds().contains(collectableId)) {
        result.put(collectableId, WatchStatus.WATCHED);
        continue;
      }
      var fileIds = collectMediaFileIds(List.of(collectableId), data);
      result.put(collectableId, deriveProgressStatus(fileIds, data.progressMap()));
    }
    return result;
  }

  public Map<UUID, WatchStatus> getWatchStatusForSeasons(UUID userId, Collection<UUID> seasonIds) {
    return aggregateStatusByGroup(userId, episodeRepository.findEpisodeIdsBySeasonIds(seasonIds));
  }

  public Map<UUID, WatchStatus> getWatchStatusForSeries(UUID userId, Collection<UUID> seriesIds) {
    var seasonIdsBySeriesId = seasonRepository.findSeasonIdsBySeriesIds(seriesIds);
    if (seasonIdsBySeriesId.isEmpty()) {
      return Map.of();
    }

    var episodeIdsBySeasonId =
        episodeRepository.findEpisodeIdsBySeasonIds(flatten(seasonIdsBySeriesId));
    if (episodeIdsBySeasonId.isEmpty()) {
      return Map.of();
    }

    var episodeIdsBySeriesId = new HashMap<UUID, List<UUID>>();
    for (var entry : seasonIdsBySeriesId.entrySet()) {
      episodeIdsBySeriesId.put(
          entry.getKey(),
          entry.getValue().stream()
              .flatMap(seasonId -> episodeIdsBySeasonId.getOrDefault(seasonId, List.of()).stream())
              .toList());
    }
    return aggregateStatusByGroup(userId, episodeIdsBySeriesId);
  }

  private Map<UUID, WatchStatus> aggregateStatusByGroup(
      UUID userId, Map<UUID, List<UUID>> episodeIdsByGroup) {
    if (episodeIdsByGroup.isEmpty()) {
      return Map.of();
    }

    var data = fetchCollectableMediaData(userId, flatten(episodeIdsByGroup));

    var result = new HashMap<UUID, WatchStatus>();
    for (var entry : episodeIdsByGroup.entrySet()) {
      var episodeIds = entry.getValue();
      var mediaFileIds = collectMediaFileIds(episodeIds, data);
      result.put(
          entry.getKey(),
          deriveAggregateStatus(episodeIds, data.watchedIds(), mediaFileIds, data.progressMap()));
    }
    return result;
  }

  private record CollectableMediaData(
      Set<UUID> watchedIds,
      Map<UUID, List<MediaFile>> mediaFilesByCollectableId,
      Map<UUID, SessionProgress> progressMap) {}

  private CollectableMediaData fetchCollectableMediaData(
      UUID userId, Collection<UUID> collectableIds) {
    var watchedIds = findWatchedCollectableIds(userId, collectableIds);
    var mediaFiles = mediaFileRepository.findByMediaIdIn(collectableIds);
    var mediaFilesByCollectableId =
        mediaFiles.stream().collect(Collectors.groupingBy(MediaFile::getMediaId));
    var progressMap =
        getProgressForMediaFiles(userId, mediaFiles.stream().map(MediaFile::getId).toList());
    return new CollectableMediaData(watchedIds, mediaFilesByCollectableId, progressMap);
  }

  private static List<UUID> collectMediaFileIds(
      List<UUID> collectableIds, CollectableMediaData data) {
    return collectableIds.stream()
        .flatMap(id -> data.mediaFilesByCollectableId().getOrDefault(id, List.of()).stream())
        .map(MediaFile::getId)
        .toList();
  }

  private static List<UUID> flatten(Map<UUID, List<UUID>> idsByGroup) {
    return idsByGroup.values().stream().flatMap(Collection::stream).toList();
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
    return countInProgress(mediaFileIds, progressMap) > 0
        ? WatchStatus.IN_PROGRESS
        : WatchStatus.UNWATCHED;
  }

  private static int countInProgress(
      List<UUID> mediaFileIds, Map<UUID, SessionProgress> progressMap) {
    return (int)
        mediaFileIds.stream()
            .map(progressMap::get)
            .filter(sp -> sp != null && sp.getPositionSeconds() > 0)
            .count();
  }
}
