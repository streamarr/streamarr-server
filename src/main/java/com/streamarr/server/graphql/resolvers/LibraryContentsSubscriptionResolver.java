package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsSubscription;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.Library;
import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dto.MediaEdge;
import com.streamarr.server.graphql.inputs.MediaFilterInput;
import com.streamarr.server.graphql.inputs.MediaFilterMapper;
import com.streamarr.server.graphql.inputs.MediaFilterRequest;
import com.streamarr.server.graphql.inputs.MediaSortInput;
import com.streamarr.server.graphql.subscriptions.KeyedSubscriptionBroadcaster;
import com.streamarr.server.graphql.subscriptions.LibraryContentsBroadcasters;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.events.library.LibraryItemAddedEvent;
import com.streamarr.server.services.events.library.LibraryItemChangedEvent;
import com.streamarr.server.services.events.library.LibraryItemRemovedEvent;
import com.streamarr.server.services.events.library.LibraryItemUpdatedEvent;
import com.streamarr.server.services.library.LibraryContentsService;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import graphql.schema.DataFetchingEnvironment;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@DgsComponent
@Builder
@RequiredArgsConstructor
public class LibraryContentsSubscriptionResolver {

  private final AuthorizationService authorization;
  private final LibraryContentsService contents;
  private final LibraryContentsBroadcasters broadcasters;
  private final RelayConnectionAdapter connections;

  @DgsSubscription
  public Publisher<LibraryItemAddedEvent> libraryItemAdded(@InputArgument String libraryId) {
    return register(libraryId, broadcasters.getAdditions());
  }

  @DgsData(parentType = "LibraryItemChange")
  public Optional<BaseCollectable<?>> item(DataFetchingEnvironment environment) {
    return contents.findItem(environment.getSource());
  }

  @DgsData(parentType = "LibraryItemChange")
  public Optional<MediaEdge<BaseCollectable<?>>> edge(
      @InputArgument MediaSortInput sort,
      @InputArgument MediaFilterInput filter,
      DataFetchingEnvironment environment) {
    LibraryItemChangedEvent change = environment.getSource();
    var effectiveFilter =
        MediaFilterMapper.from(
            MediaFilterRequest.builder()
                .libraryId(change.libraryId())
                .profileId(authorization.requireProfile())
                .sort(sort)
                .filter(filter)
                .build());
    var options = MediaPaginationOptions.builder().mediaFilter(effectiveFilter).build();
    return contents
        .findMatchingItem(change, effectiveFilter)
        .map(item -> connections.toEdge(item, options));
  }

  @DgsSubscription
  public Publisher<LibraryItemUpdatedEvent> libraryItemUpdated(@InputArgument String libraryId) {
    var profileId = authorization.currentIdentity().profileId();
    return register(libraryId, broadcasters.getUpdates())
        .filter(change -> change.audience().includes(profileId));
  }

  private UUID parseId(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException _) {
      throw new InvalidIdException(id);
    }
  }

  @DgsSubscription
  public Publisher<LibraryItemRemovedEvent> libraryItemRemoved(@InputArgument String libraryId) {
    return register(libraryId, broadcasters.getRemovals());
  }

  @DgsSubscription
  public Publisher<Library> libraryCountsChanged(@InputArgument String libraryId) {
    return register(libraryId, broadcasters.getCounts())
        .flatMap(id -> Mono.justOrEmpty(contents.findLibrary(id)));
  }

  private <T> Flux<T> register(
      String libraryId, KeyedSubscriptionBroadcaster<UUID, T> broadcaster) {
    contents.requireSubscriptionProfile(authorization.currentIdentity());
    var id = parseId(libraryId);
    return broadcaster.register(id, () -> contents.findLibrary(id).isPresent());
  }
}
