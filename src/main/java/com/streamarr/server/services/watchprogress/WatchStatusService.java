package com.streamarr.server.services.watchprogress;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.streaming.WatchProgress;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.streaming.WatchProgressRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WatchStatusService {

  private final WatchProgressRepository watchProgressRepository;
  private final MediaFileRepository mediaFileRepository;
  private final EpisodeRepository episodeRepository;
  private final SeasonRepository seasonRepository;

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
    var episodeIdsBySeasonId = episodeRepository.findEpisodeIdsBySeasonIds(seasonIds);
    if (episodeIdsBySeasonId.isEmpty()) {
      return Map.of();
    }

    var allEpisodeIds = episodeIdsBySeasonId.values().stream().flatMap(Collection::stream).toList();
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
              .flatMap(epId -> mediaFilesByEpisodeId.getOrDefault(epId, List.of()).stream())
              .map(MediaFile::getId)
              .toList();

      result.put(entry.getKey(), deriveWatchStatusFromFileIds(seasonMediaFileIds, progressMap));
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
          collectMediaFileIds(seriesEntry.getValue(), episodeIdsBySeasonId, mediaFilesByEpisodeId);

      result.put(
          seriesEntry.getKey(), deriveWatchStatusFromFileIds(seriesMediaFileIds, progressMap));
    }
    return result;
  }

  private static List<UUID> collectMediaFileIds(
      List<UUID> seasonIds,
      Map<UUID, List<UUID>> episodeIdsBySeasonId,
      Map<UUID, List<MediaFile>> mediaFilesByEpisodeId) {
    return seasonIds.stream()
        .flatMap(seasonId -> episodeIdsBySeasonId.getOrDefault(seasonId, List.of()).stream())
        .flatMap(epId -> mediaFilesByEpisodeId.getOrDefault(epId, List.of()).stream())
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
