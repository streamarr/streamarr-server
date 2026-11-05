package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.exceptions.SeriesNotFoundException;
import com.streamarr.server.services.SeriesService;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;

@DgsComponent
@RequiredArgsConstructor
public class SeasonFieldResolver {

  private static final String SERIES_LOADER = "series";

  private final SeriesService seriesService;

  @DgsData(parentType = "Season", field = "episodes")
  public List<Episode> episodes(DataFetchingEnvironment dfe) {
    Season season = dfe.getSource();
    return seriesService.findEpisodes(season.getId());
  }

  @DgsData(parentType = "Season", field = "series")
  public CompletableFuture<Series> series(DataFetchingEnvironment dfe) {
    Season season = dfe.getSource();
    var seriesId = season.getSeries().getId();
    DataLoader<UUID, Series> loader = dfe.getDataLoader(SERIES_LOADER);
    return loader
        .load(seriesId)
        .thenApply(
            series ->
                Optional.ofNullable(series)
                    .orElseThrow(() -> new SeriesNotFoundException(seriesId, season.getId())));
  }
}
