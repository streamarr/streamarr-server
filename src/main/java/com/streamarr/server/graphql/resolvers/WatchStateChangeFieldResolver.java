package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.graphql.dto.WatchStateChange;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import graphql.schema.DataFetchingEnvironment;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class WatchStateChangeFieldResolver {

  private final MovieService movieService;
  private final SeriesService seriesService;

  // Runs on a delivery thread for each subscriber, so every subscriber reads the items as they are
  // when the change reaches it. An item removed since the change is left out.
  @DgsData(parentType = "WatchStateChange", field = "items")
  public List<BaseCollectable<?>> items(DataFetchingEnvironment dfe) {
    WatchStateChange change = dfe.getSource();
    var itemIds = change.itemIds();
    var items = new ArrayList<BaseCollectable<?>>();
    items.addAll(movieService.findMoviesByIds(itemIds));
    items.addAll(seriesService.findEpisodesByIds(itemIds));
    items.addAll(seriesService.findSeasonsByEpisodeIds(itemIds));
    items.addAll(seriesService.findSeriesByEpisodeIds(itemIds));
    return items;
  }
}
