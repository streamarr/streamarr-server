package com.streamarr.server.graphql.dataloaders;

import com.netflix.graphql.dgs.DgsDataLoader;
import com.streamarr.server.domain.media.Season;
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

/** Batches the seasons in one response, keyed by season id; a missing season has no entry. */
@DgsDataLoader(name = "seasons")
@RequiredArgsConstructor
public class SeasonDataLoader implements MappedBatchLoader<UUID, Season> {

  private final SeriesService seriesService;

  @Override
  public CompletionStage<Map<UUID, Season>> load(Set<UUID> seasonIds) {
    return CompletableFuture.completedFuture(
        seriesService.findSeasonsByIds(seasonIds).stream()
            .collect(Collectors.toMap(Season::getId, Function.identity())));
  }
}
