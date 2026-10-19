package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsSubscription;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.Collectable;
import com.streamarr.server.domain.Library;
import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.dto.LibraryItemPosition;
import com.streamarr.server.graphql.inputs.MediaFilterInput;
import com.streamarr.server.graphql.inputs.MediaFilterMapper;
import com.streamarr.server.graphql.inputs.MediaFilterRequest;
import com.streamarr.server.graphql.inputs.MediaSortInput;
import com.streamarr.server.graphql.subscriptions.KeyedSubscriptionBroadcaster;
import com.streamarr.server.graphql.subscriptions.LibraryContentsBroadcasters;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.events.library.LibraryItemChangedEvent;
import com.streamarr.server.services.library.LibraryContentsService;
import com.streamarr.server.services.pagination.MediaOrder;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import graphql.schema.DataFetchingEnvironment;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

@DgsComponent
@RequiredArgsConstructor
public class LibraryContentsSubscriptionResolver {

  private final AuthorizationService authorization;
  private final LibraryContentsService contents;
  private final LibraryContentsBroadcasters broadcasters;
  private final CursorUtil cursors;

  @DgsSubscription
  public Publisher<LibraryItemChangedEvent> libraryItemAdded(@InputArgument String libraryId) {
    return register(libraryId, broadcasters.getAdditions());
  }

  @DgsData(parentType = "LibraryItemChange")
  public Collectable item(DataFetchingEnvironment environment) {
    return contents.findItem(environment.getSource()).orElse(null);
  }

  @DgsData(parentType = "LibraryItemChange")
  public LibraryItemPosition position(
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
    var value = contents.findPosition(change, effectiveFilter);
    var cursor =
        value.map(
            found ->
                cursors
                    .encodeMediaCursor(
                        MediaPaginationOptions.builder().mediaFilter(effectiveFilter).build(),
                        change.itemId(),
                        found.value())
                    .getValue());
    return new LibraryItemPosition(
        cursor.isPresent(),
        cursor.orElse(null),
        value
            .map(found -> MediaOrder.from(effectiveFilter, change.itemId(), found.value()))
            .orElse(null));
  }

  @DgsSubscription
  public Publisher<LibraryItemChangedEvent> libraryItemUpdated(@InputArgument String libraryId) {
    var profileId = authorization.requireProfile();
    return register(libraryId, broadcasters.getUpdates())
        .filter(change -> change.profileId().map(profileId::equals).orElse(true));
  }

  private UUID parseId(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException _) {
      throw new InvalidIdException(id);
    }
  }

  @DgsSubscription
  public Publisher<LibraryItemChangedEvent> libraryItemRemoved(@InputArgument String libraryId) {
    return register(libraryId, broadcasters.getRemovals());
  }

  @DgsSubscription
  public Publisher<Library> libraryCountsChanged(@InputArgument String libraryId) {
    return register(libraryId, broadcasters.getCounts())
        .mapNotNull(id -> contents.findLibrary(id).orElse(null));
  }

  private <T> Flux<T> register(
      String libraryId, KeyedSubscriptionBroadcaster<UUID, T> broadcaster) {
    authorization.requireProfile();
    var id = parseId(libraryId);
    return broadcaster.register(id, () -> contents.findLibrary(id).isPresent());
  }
}
