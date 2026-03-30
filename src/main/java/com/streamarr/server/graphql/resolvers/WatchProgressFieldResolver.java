package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.graphql.dto.WatchProgressDto;
import com.streamarr.server.services.watchprogress.WatchProgressService;
import graphql.schema.DataFetchingEnvironment;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;

@DgsComponent
@RequiredArgsConstructor
public class WatchProgressFieldResolver {

  private final WatchProgressService watchProgressService;

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
  public WatchStatus movieWatchStatus(DataFetchingEnvironment dfe) {
    Movie movie = dfe.getSource();
    return resolveWatchStatus(movie.getId());
  }

  @DgsData(parentType = "Episode", field = "watchStatus")
  public WatchStatus episodeWatchStatus(DataFetchingEnvironment dfe) {
    Episode episode = dfe.getSource();
    return resolveWatchStatus(episode.getId());
  }

  @DgsData(parentType = "Season", field = "watchStatus")
  public WatchStatus seasonWatchStatus(DataFetchingEnvironment dfe) {
    Season season = dfe.getSource();
    return resolveWatchStatus(season.getId());
  }

  @DgsData(parentType = "Series", field = "watchStatus")
  public WatchStatus seriesWatchStatus(DataFetchingEnvironment dfe) {
    Series series = dfe.getSource();
    return resolveWatchStatus(series.getId());
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
        .thenApply(results -> results.stream().filter(dto -> dto != null).findFirst().orElse(null));
  }

  private WatchStatus resolveWatchStatus(UUID collectableId) {
    // TODO: Replace with authenticated user ID from Spring Security
    var userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    return watchProgressService.getWatchStatusForCollectable(userId, collectableId);
  }
}
