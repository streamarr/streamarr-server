package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.DgsTypeResolver;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.graphql.dataloaders.ContinueWatchingPositionLoaderKey;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.pagination.PaginationService;
import com.streamarr.server.services.watchprogress.ContinueWatchingPosition;
import com.streamarr.server.services.watchprogress.ContinueWatchingService;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;

@DgsComponent
@RequiredArgsConstructor
public class ContinueWatchingResolver {

  private final ContinueWatchingService continueWatchingService;
  private final AuthorizationService authorizationService;
  private final PaginationService paginationService;

  @DgsQuery
  public List<BaseCollectable<?>> continueWatching(DataFetchingEnvironment dfe) {
    int first = dfe.getArgumentOrDefault("first", 20);
    var limit = paginationService.getPaginationOptions(first, null, 0, null).getLimit();
    return continueWatchingService.getContinueWatching(
        authorizationService.requireProfile(), limit);
  }

  @DgsData(parentType = "Movie", field = "continueWatchingPosition")
  public CompletableFuture<ContinueWatchingPosition> moviePosition(DataFetchingEnvironment dfe) {
    Movie movie = dfe.getSource();
    return position(dfe, movie.getId());
  }

  @DgsData(parentType = "Episode", field = "continueWatchingPosition")
  public CompletableFuture<ContinueWatchingPosition> episodePosition(DataFetchingEnvironment dfe) {
    Episode episode = dfe.getSource();
    return position(dfe, episode.getId());
  }

  private CompletableFuture<ContinueWatchingPosition> position(
      DataFetchingEnvironment dfe, UUID itemId) {
    DataLoader<ContinueWatchingPositionLoaderKey, ContinueWatchingPosition> loader =
        dfe.getDataLoader("continueWatchingPosition");
    return loader.load(
        new ContinueWatchingPositionLoaderKey(authorizationService.requireProfile(), itemId));
  }

  @DgsTypeResolver(name = "ContinueWatchingMedia")
  public String resolveContinueWatchingMedia(Object media) {
    return switch (media) {
      case Movie _ -> "Movie";
      case Episode _ -> "Episode";
      default ->
          throw new IllegalArgumentException(
              "Unknown continue watching media type: " + media.getClass().getSimpleName());
    };
  }
}
