package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.DgsTypeResolver;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.CursorValidator;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dto.ContinueWatchingChange;
import com.streamarr.server.graphql.dto.WatchStateChange;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.pagination.MediaPaginationOptionsResolver;
import com.streamarr.server.services.pagination.PaginationService;
import com.streamarr.server.services.watchprogress.ContinueWatchingService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class ContinueWatchingResolver {

  private final ContinueWatchingService continueWatchingService;
  private final AuthorizationService authorizationService;
  private final PaginationService paginationService;
  private final RelayConnectionAdapter connections;
  private final CursorUtil cursors;
  private final CursorValidator cursorValidator;

  @DgsQuery
  public Connection<BaseCollectable<?>> continueWatching(DataFetchingEnvironment dfe) {
    int first = dfe.getArgumentOrDefault("first", 20);
    var pagination =
        paginationService.getPaginationOptions(first, dfe.getArgument("after"), 0, null);
    var profileId = authorizationService.requireProfile();
    var options =
        MediaPaginationOptionsResolver.resolve(
            pagination,
            ContinueWatchingService.listOptions(profileId).getMediaFilter(),
            cursors::decodeMediaCursor,
            cursorValidator::validateCursorAgainstFilter);
    return connections.toConnection(continueWatchingService.getPage(profileId, options), options);
  }

  @DgsData(parentType = "WatchStateChange", field = "continueWatching")
  public List<ContinueWatchingChange> changes(DataFetchingEnvironment dfe) {
    WatchStateChange change = dfe.getSource();
    var profileId = authorizationService.requireProfile();
    var items = continueWatchingService.findMatchingItems(profileId, change.itemIds());
    var options = ContinueWatchingService.listOptions(profileId);
    return change.itemIds().stream()
        .map(
            id ->
                new ContinueWatchingChange(
                    id,
                    Optional.ofNullable(items.get(id))
                        .map(item -> connections.toEdge(item, options))
                        .orElse(null)))
        .toList();
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
