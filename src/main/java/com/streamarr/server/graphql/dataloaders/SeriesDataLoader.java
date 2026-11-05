package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.services.SeriesService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.dataloader.MappedBatchLoader;

/** Batches the series in one response, keyed by series id; a missing series has no entry. */
@DgsDataLoader(name = "series")
@RequiredArgsConstructor
public class SeriesDataLoader implements MappedBatchLoader<UUID, Series> {

  private final SeriesService seriesService;

  @Override
  public CompletionStage<Map<UUID, Series>> load(Set<UUID> seriesIds) {
    return CompletableFuture.completedFuture(
        seriesService.findSeriesByIds(seriesIds).stream()
            .collect(Collectors.toMap(Series::getId, Function.identity())));
  }
}
