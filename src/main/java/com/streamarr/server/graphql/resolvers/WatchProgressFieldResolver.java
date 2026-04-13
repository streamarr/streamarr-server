package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.graphql.dataloaders.WatchStatusEntityType;
import com.streamarr.server.graphql.dataloaders.WatchStatusLoaderKey;
import com.streamarr.server.graphql.dto.WatchProgressDto;
import graphql.schema.DataFetchingEnvironment;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.dataloader.DataLoader;

@DgsComponent
public class WatchProgressFieldResolver {

  @DgsData(parentType = "Movie", field = "watchProgress")
  public CompletableFuture<WatchProgressDto> movieWatchProgress(DataFetchingEnvironment dfe) {
    Movie movie = dfe.getSource();
    return loadProgress(dfe, movie.getFiles());
  }

  @DgsData(parentType = "Episode", field = "watchProgress")
  public CompletableFuture<WatchProgressDto> episodeWatchProgress(DataFetchingEnvironment dfe) {
    Episode episode = dfe.getSource();
    return loadProgress(dfe, episode.getFiles());
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
      DataFetchingEnvironment dfe, Set<MediaFile> files) {
    if (files == null || files.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }

    DataLoader<UUID, WatchProgressDto> loader = dfe.getDataLoader("watchProgress");
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

  private CompletableFuture<WatchStatus> loadWatchStatus(
      DataFetchingEnvironment dfe, UUID entityId, WatchStatusEntityType entityType) {
    DataLoader<WatchStatusLoaderKey, WatchStatus> loader = dfe.getDataLoader("watchStatus");
    return loader.load(new WatchStatusLoaderKey(entityId, entityType));
  }
}
