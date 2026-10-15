package com.streamarr.server.services.watchprogress;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.domain.streaming.WatchHistory;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.watchprogress.events.WatchStatusChangedEvent;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BinaryOperator;
import java.util.function.Function;
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
  private final MovieRepository movieRepository;
  private final EpisodeRepository episodeRepository;
  private final SeasonRepository seasonRepository;
  private final SeriesRepository seriesRepository;
  private final ApplicationEventPublisher eventPublisher;

  @Transactional
  public Outcome<BaseCollectable<?>, MarkWatchedRejection> markWatched(
      UUID profileId, UUID itemId) {
    var item = findItem(itemId);
    if (item.isEmpty()) {
      return Outcome.rejected(new MarkWatchedRejection.ItemNotFound(itemId));
    }

    markWatchedInternal(profileId, itemId, scopeOf(item.get()), 0);
    return Outcome.accepted(item.get());
  }

  @Transactional
  public void markWatched(
      UUID profileId, UUID collectableId, CollectableScope scope, int durationSeconds) {
    markWatchedInternal(profileId, collectableId, scope, durationSeconds);
  }

  private void markWatchedInternal(
      UUID profileId, UUID collectableId, CollectableScope scope, int durationSeconds) {
    var leafIds = resolveLeafCollectableIds(collectableId, scope);
    watchHistoryRepository.batchInsert(profileId, leafIds, durationSeconds);

    eventPublisher.publishEvent(new WatchStatusChangedEvent(profileId, collectableId));
  }

  @Transactional
  public Outcome<BaseCollectable<?>, MarkUnwatchedRejection> markUnwatched(
      UUID profileId, UUID itemId) {
    var item = findItem(itemId);
    if (item.isEmpty()) {
      return Outcome.rejected(new MarkUnwatchedRejection.ItemNotFound(itemId));
    }

    markUnwatchedInternal(profileId, itemId, scopeOf(item.get()));
    return Outcome.accepted(item.get());
  }

  @Transactional
  public void markUnwatched(UUID profileId, UUID collectableId, CollectableScope scope) {
    markUnwatchedInternal(profileId, collectableId, scope);
  }

  private void markUnwatchedInternal(UUID profileId, UUID collectableId, CollectableScope scope) {
    var leafIds = resolveLeafCollectableIds(collectableId, scope);
    watchHistoryRepository.dismissAll(profileId, leafIds);

    var mediaFileIds = mediaFileRepository.findMediaFileIdsByMediaIds(leafIds);
    if (!mediaFileIds.isEmpty()) {
      sessionProgressRepository.deleteByProfileIdAndMediaFileIds(profileId, mediaFileIds);
    }

    eventPublisher.publishEvent(new WatchStatusChangedEvent(profileId, collectableId));
  }

  private Optional<BaseCollectable<?>> findItem(UUID itemId) {
    return movieRepository
        .findById(itemId)
        .<BaseCollectable<?>>map(movie -> movie)
        .or(() -> episodeRepository.findById(itemId))
        .or(() -> seasonRepository.findById(itemId))
        .or(() -> seriesRepository.findById(itemId));
  }

  private static CollectableScope scopeOf(BaseCollectable<?> item) {
    return switch (item) {
      case Season _ -> CollectableScope.SEASON;
      case Series _ -> CollectableScope.SERIES;
      default -> CollectableScope.DIRECT_MEDIA;
    };
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
      UUID profileId, Collection<UUID> collectableIds) {
    var states = fetchItemWatchStates(profileId, collectableIds);

    var result = new HashMap<UUID, WatchStatus>();
    for (var collectableId : collectableIds) {
      result.put(collectableId, states.get(collectableId).status());
    }
    return result;
  }

  public Map<UUID, WatchProgressDto> getProgressForDirectMedia(
      UUID profileId, Collection<UUID> collectableIds) {
    var result = new HashMap<UUID, WatchProgressDto>();
    fetchItemWatchStates(profileId, collectableIds)
        .forEach(
            (collectableId, state) ->
                state
                    .resumePosition()
                    .ifPresent(
                        progress -> result.put(collectableId, WatchProgressDto.from(progress))));
    return result;
  }

  public Map<UUID, WatchStatus> getWatchStatusForSeasons(
      UUID profileId, Collection<UUID> seasonIds) {
    return aggregateStatusByGroup(
        profileId, episodeRepository.findEpisodeIdsBySeasonIds(seasonIds));
  }

  public Map<UUID, WatchStatus> getWatchStatusForSeries(
      UUID profileId, Collection<UUID> seriesIds) {
    return aggregateStatusByGroup(profileId, findEpisodeIdsBySeriesIds(seriesIds));
  }

  public Map<UUID, WatchProgressDto> getAggregateProgressForSeasons(
      UUID profileId, Collection<UUID> seasonIds) {
    return aggregateProgressByGroup(
        profileId, episodeRepository.findEpisodeIdsBySeasonIds(seasonIds));
  }

  public Map<UUID, WatchProgressDto> getAggregateProgressForSeries(
      UUID profileId, Collection<UUID> seriesIds) {
    return aggregateProgressByGroup(profileId, findEpisodeIdsBySeriesIds(seriesIds));
  }

  private Map<UUID, List<UUID>> findEpisodeIdsBySeriesIds(Collection<UUID> seriesIds) {
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
    return episodeIdsBySeriesId;
  }

  private Map<UUID, WatchStatus> aggregateStatusByGroup(
      UUID profileId, Map<UUID, List<UUID>> episodeIdsByGroup) {
    if (episodeIdsByGroup.isEmpty()) {
      return Map.of();
    }

    var states = fetchItemWatchStates(profileId, flatten(episodeIdsByGroup));

    var result = new HashMap<UUID, WatchStatus>();
    for (var entry : episodeIdsByGroup.entrySet()) {
      var episodeStatuses =
          entry.getValue().stream().map(episodeId -> states.get(episodeId).status()).toList();
      result.put(entry.getKey(), aggregateStatus(episodeStatuses));
    }
    return result;
  }

  private static WatchStatus aggregateStatus(List<WatchStatus> episodeStatuses) {
    if (!episodeStatuses.isEmpty()
        && episodeStatuses.stream().allMatch(WatchStatus.WATCHED::equals)) {
      return WatchStatus.WATCHED;
    }

    if (episodeStatuses.stream().anyMatch(status -> status != WatchStatus.UNWATCHED)) {
      return WatchStatus.IN_PROGRESS;
    }

    return WatchStatus.UNWATCHED;
  }

  private Map<UUID, WatchProgressDto> aggregateProgressByGroup(
      UUID profileId, Map<UUID, List<UUID>> episodeIdsByGroup) {
    if (episodeIdsByGroup.isEmpty()) {
      return Map.of();
    }

    var states = fetchItemWatchStates(profileId, flatten(episodeIdsByGroup));

    var result = new HashMap<UUID, WatchProgressDto>();
    for (var entry : episodeIdsByGroup.entrySet()) {
      entry.getValue().stream()
          .flatMap(episodeId -> states.get(episodeId).resumePosition().stream())
          .max(Comparator.comparing(SessionProgress::getLastModifiedOn))
          .ifPresent(progress -> result.put(entry.getKey(), WatchProgressDto.from(progress)));
    }
    return result;
  }

  private record ItemWatchState(boolean completed, Optional<SessionProgress> resumePosition) {

    WatchStatus status() {
      if (resumePosition.isPresent()) {
        return WatchStatus.IN_PROGRESS;
      }

      if (completed) {
        return WatchStatus.WATCHED;
      }

      return WatchStatus.UNWATCHED;
    }
  }

  // Progress counts only when the database stamped the progress after the latest completion that
  // has not been dismissed. When one device pauses and another device then finishes the title,
  // the title shows no resume position until the paused device plays again.
  private Map<UUID, ItemWatchState> fetchItemWatchStates(
      UUID profileId, Collection<UUID> collectableIds) {
    var latestCompletionById = findLatestCompletions(profileId, collectableIds);
    var latestProgressById = findLatestProgressWithPosition(profileId, collectableIds);

    var result = new HashMap<UUID, ItemWatchState>();
    for (var collectableId : collectableIds) {
      var completion = latestCompletionById.get(collectableId);
      var resumePosition =
          Optional.ofNullable(latestProgressById.get(collectableId))
              .filter(
                  progress ->
                      completion == null || progress.getLastModifiedOn().isAfter(completion));
      result.put(collectableId, new ItemWatchState(completion != null, resumePosition));
    }
    return result;
  }

  private Map<UUID, Instant> findLatestCompletions(
      UUID profileId, Collection<UUID> collectableIds) {
    return watchHistoryRepository
        .findByProfileIdAndCollectableIdIn(profileId, collectableIds)
        .stream()
        .filter(history -> history.getDismissedAt() == null)
        .collect(
            Collectors.toMap(
                WatchHistory::getCollectableId,
                WatchHistory::getWatchedAt,
                BinaryOperator.maxBy(Comparator.naturalOrder())));
  }

  private Map<UUID, SessionProgress> findLatestProgressWithPosition(
      UUID profileId, Collection<UUID> collectableIds) {
    var collectableIdByMediaFileId =
        mediaFileRepository.findByMediaIdIn(collectableIds).stream()
            .collect(Collectors.toMap(MediaFile::getId, MediaFile::getMediaId));
    if (collectableIdByMediaFileId.isEmpty()) {
      return Map.of();
    }

    return sessionProgressRepository
        .findByProfileIdAndMediaFileIdIn(profileId, collectableIdByMediaFileId.keySet())
        .stream()
        .filter(progress -> progress.getPositionSeconds() > 0)
        .collect(
            Collectors.toMap(
                progress -> collectableIdByMediaFileId.get(progress.getMediaFileId()),
                Function.identity(),
                BinaryOperator.maxBy(Comparator.comparing(SessionProgress::getLastModifiedOn))));
  }

  private static List<UUID> flatten(Map<UUID, List<UUID>> idsByGroup) {
    return idsByGroup.values().stream().flatMap(Collection::stream).toList();
  }
}
