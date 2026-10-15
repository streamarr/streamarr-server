package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

/**
 * Batches the unmatched fact of movies and series. A batch may mix movie and series ids, so it asks
 * both services; each one answers only for its own ids.
 */
@DgsDataLoader(name = "unmatched")
@RequiredArgsConstructor
public class UnmatchedDataLoader implements MappedBatchLoader<UUID, Boolean> {

  private final MovieService movieService;
  private final SeriesService seriesService;

  @Override
  public CompletionStage<Map<UUID, Boolean>> load(Set<UUID> itemIds) {
    var unmatchedIds = new HashSet<>(movieService.findUnmatchedMovieIds(itemIds));
    unmatchedIds.addAll(seriesService.findUnmatchedSeriesIds(itemIds));
    return CompletableFuture.completedFuture(
        itemIds.stream().collect(Collectors.toMap(Function.identity(), unmatchedIds::contains)));
  }
}
