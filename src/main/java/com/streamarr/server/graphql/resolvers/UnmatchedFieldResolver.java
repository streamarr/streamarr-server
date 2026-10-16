package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Series;
import graphql.schema.DataFetchingEnvironment;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.dataloader.DataLoader;

@DgsComponent
public class UnmatchedFieldResolver {

  private static final String UNMATCHED_LOADER = "unmatched";

  @DgsData(parentType = "Movie", field = "unmatched")
  public CompletableFuture<Boolean> movieUnmatched(DataFetchingEnvironment dfe) {
    Movie movie = dfe.getSource();
    return loadUnmatched(dfe, movie.getId());
  }

  @DgsData(parentType = "Series", field = "unmatched")
  public CompletableFuture<Boolean> seriesUnmatched(DataFetchingEnvironment dfe) {
    Series series = dfe.getSource();
    return loadUnmatched(dfe, series.getId());
  }

  private static CompletableFuture<Boolean> loadUnmatched(
      DataFetchingEnvironment dfe, UUID itemId) {
    DataLoader<UUID, Boolean> loader = dfe.getDataLoader(UNMATCHED_LOADER);
    return loader.load(itemId);
  }
}
