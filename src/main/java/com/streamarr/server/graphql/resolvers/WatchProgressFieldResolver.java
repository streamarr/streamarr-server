package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.graphql.dataloaders.WatchProgressLoaderKey;
import com.streamarr.server.graphql.dataloaders.WatchStatusEntityType;
import com.streamarr.server.graphql.dataloaders.WatchStatusLoaderKey;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.watchprogress.WatchProgressDto;
import graphql.schema.DataFetchingEnvironment;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;

@DgsComponent
@RequiredArgsConstructor
public class WatchProgressFieldResolver {

  private static final String WATCH_PROGRESS_LOADER = "watchProgress";
  private static final String AGGREGATE_WATCH_PROGRESS_LOADER = "aggregateWatchProgress";
  private static final String WATCH_STATUS_LOADER = "watchStatus";

  private final MovieService movieService;
  private final SeriesService seriesService;

  @DgsData(parentType = "Movie", field = "watchProgress")
  public CompletableFuture<WatchProgressDto> movieWatchProgress(DataFetchingEnvironment dfe) {
    Movie movie = dfe.getSource();
    return loadProgress(dfe, movieService.findMediaFiles(movie.getId()));
  }

  @DgsData(parentType = "Episode", field = "watchProgress")
  public CompletableFuture<WatchProgressDto> episodeWatchProgress(DataFetchingEnvironment dfe) {
    Episode episode = dfe.getSource();
    return loadProgress(dfe, seriesService.findMediaFiles(episode.getId()));
  }

  @DgsData(parentType = "Series", field = "watchProgress")
  public CompletableFuture<WatchProgressDto> seriesWatchProgress(DataFetchingEnvironment dfe) {
    Series series = dfe.getSource();
    return loadAggregateProgress(dfe, series.getId(), WatchStatusEntityType.SERIES);
  }

  @DgsData(parentType = "Season", field = "watchProgress")
  public CompletableFuture<WatchProgressDto> seasonWatchProgress(DataFetchingEnvironment dfe) {
    Season season = dfe.getSource();
    return loadAggregateProgress(dfe, season.getId(), WatchStatusEntityType.SEASON);
  }

  @DgsData(parentType = "Movie", field = "watchStatus")
  public CompletableFuture<WatchStatus> movieWatchStatus(DataFetchingEnvironment dfe) {
    Movie movie = dfe.getSource();
    return loadWatchStatus(dfe, movie.getId(), WatchStatusEntityType.DIRECT_MEDIA);
  }

  @DgsData(parentType = "Episode", field = "watchStatus")
  public CompletableFuture<WatchStatus> episodeWatchStatus(DataFetchingEnvironment dfe) {
    Episode episode = dfe.getSource();
    return loadWatchStatus(dfe, episode.getId(), WatchStatusEntityType.DIRECT_MEDIA);
  }

  @DgsData(parentType = "Season", field = "watchStatus")
  public CompletableFuture<WatchStatus> seasonWatchStatus(DataFetchingEnvironment dfe) {
    Season season = dfe.getSource();
    return loadWatchStatus(dfe, season.getId(), WatchStatusEntityType.SEASON);
  }

  @DgsData(parentType = "Series", field = "watchStatus")
  public CompletableFuture<WatchStatus> seriesWatchStatus(DataFetchingEnvironment dfe) {
    Series series = dfe.getSource();
    return loadWatchStatus(dfe, series.getId(), WatchStatusEntityType.SERIES);
  }

  private CompletableFuture<WatchProgressDto> loadProgress(
      DataFetchingEnvironment dfe, List<MediaFile> files) {
    if (files.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }

    DataLoader<UUID, WatchProgressDto> loader = dfe.getDataLoader(WATCH_PROGRESS_LOADER);
    var mediaFileIds = files.stream().map(MediaFile::getId).toList();

    return loader
        .loadMany(mediaFileIds)
        .thenApply(
            results ->
                results.stream()
                    .filter(Objects::nonNull)
                    .max(Comparator.comparing(WatchProgressDto::lastModifiedOn))
                    .orElse(null));
  }

  private CompletableFuture<WatchProgressDto> loadAggregateProgress(
      DataFetchingEnvironment dfe, UUID entityId, WatchStatusEntityType entityType) {
    DataLoader<WatchProgressLoaderKey, WatchProgressDto> loader =
        dfe.getDataLoader(AGGREGATE_WATCH_PROGRESS_LOADER);
    return loader.load(new WatchProgressLoaderKey(entityId, entityType));
  }

  private CompletableFuture<WatchStatus> loadWatchStatus(
      DataFetchingEnvironment dfe, UUID entityId, WatchStatusEntityType entityType) {
    DataLoader<WatchStatusLoaderKey, WatchStatus> loader = dfe.getDataLoader(WATCH_STATUS_LOADER);
    return loader.load(new WatchStatusLoaderKey(entityId, entityType));
  }
}
