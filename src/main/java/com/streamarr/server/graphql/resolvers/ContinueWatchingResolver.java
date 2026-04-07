package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.DgsTypeResolver;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.services.watchprogress.ContinueWatchingService;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class ContinueWatchingResolver {

  private final ContinueWatchingService continueWatchingService;

  @DgsQuery
  public List<? extends BaseCollectable<?>> continueWatching(DataFetchingEnvironment dfe) {
    int first = dfe.getArgumentOrDefault("first", 20);
    return continueWatchingService.getContinueWatching(first);
  }

  @DgsTypeResolver(name = "ContinueWatchingMedia")
  public String resolveContinueWatchingMedia(Object media) {
    if (media instanceof Movie) {
      return "Movie";
    }
    if (media instanceof Episode) {
      return "Episode";
    }
    throw new IllegalArgumentException(
        "Unknown continue watching media type: " + media.getClass().getSimpleName());
  }
}
