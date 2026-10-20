package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.DgsTypeResolver;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.graphql.cursor.PositionCursorCodec;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dto.ContinueWatchingChange;
import com.streamarr.server.graphql.dto.WatchStateChange;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.pagination.ListIdentity;
import com.streamarr.server.services.pagination.MediaOrder;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.PaginationService;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.watchprogress.ContinueWatchingList;
import com.streamarr.server.services.watchprogress.ContinueWatchingService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class ContinueWatchingResolver {

  private static final String LIST = "continue-watching";

  private final ContinueWatchingService continueWatchingService;
  private final AuthorizationService authorizationService;
  private final PaginationService paginationService;
  private final RelayConnectionAdapter connections;
  private final PositionCursorCodec cursors;

  @DgsQuery
  public Connection<BaseCollectable<?>> continueWatching(DataFetchingEnvironment dfe) {
    int first = dfe.getArgumentOrDefault("first", 20);
    var pagination =
        paginationService.getPaginationOptions(first, dfe.getArgument("after"), 0, null);
    var profileId = authorizationService.requireProfile();
    var list = listOf(profileId);
    var page = continueWatchingService.getPage(profileId, cursors.pageRequest(list, pagination));
    return connections.toOrderedConnection(page, list, ContinueWatchingResolver::orderOf);
  }

  @DgsData(parentType = "WatchStateChange", field = "continueWatching")
  public List<ContinueWatchingChange> changes(DataFetchingEnvironment dfe) {
    WatchStateChange change = dfe.getSource();
    var profileId = authorizationService.requireProfile();
    var rows = continueWatchingService.findMatchingItems(profileId, change.itemIds());
    var list = listOf(profileId);
    return change.itemIds().stream()
        .map(
            id ->
                new ContinueWatchingChange(
                    id,
                    Optional.ofNullable(rows.get(id))
                        .map(
                            row -> connections.toEdge(row, list, ContinueWatchingResolver::orderOf))
                        .orElse(null)))
        .toList();
  }

  private static ListIdentity listOf(UUID profileId) {
    return new ListIdentity(LIST, profileId);
  }

  private static MediaOrder orderOf(Position position) {
    return MediaOrder.at(
        OrderMediaBy.LAST_WATCHED, ContinueWatchingList.LAST_WATCHED_FIRST.direction(), position);
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
