package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsSubscription;
import com.streamarr.server.domain.Library;
import com.streamarr.server.graphql.dto.LibraryRemoval;
import com.streamarr.server.graphql.subscriptions.LibraryBroadcasters;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.library.LibraryQueryService;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;

@DgsComponent
@RequiredArgsConstructor
public class LibrarySubscriptionResolver {

  private final AuthorizationService authorizationService;
  private final LibraryQueryService libraryQueries;
  private final LibraryBroadcasters broadcasters;

  @DgsSubscription
  public Publisher<Library> libraryStatusChanged() {
    requireSubscriptionProfile();
    return broadcasters.getStatusChanges().register();
  }

  @DgsSubscription
  public Publisher<Library> libraryAdded() {
    requireSubscriptionProfile();
    return broadcasters.getAdditions().register();
  }

  @DgsSubscription
  public Publisher<LibraryRemoval> libraryRemoved() {
    requireSubscriptionProfile();
    return broadcasters.getRemovals().register();
  }

  private void requireSubscriptionProfile() {
    libraryQueries.requireSubscriptionProfile(authorizationService.currentIdentity());
  }
}
