package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsSubscription;
import com.streamarr.server.domain.Library;
import com.streamarr.server.graphql.dto.RemovedLibrary;
import com.streamarr.server.graphql.subscriptions.LibraryBroadcasters;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.library.LibraryAdministrationService;
import com.streamarr.server.services.library.LibraryQueryService;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;

@DgsComponent
@RequiredArgsConstructor
public class LibrarySubscriptionResolver {

  private final AuthorizationService authorizationService;
  private final LibraryAdministrationService libraryAdministration;
  private final LibraryQueryService libraryQueries;
  private final LibraryBroadcasters broadcasters;

  @DgsSubscription
  public Publisher<Library> libraryStatusChanged() {
    libraryAdministration.requireViewLibraryAdministration(authorizationService.currentIdentity());
    return broadcasters.getStatusChanges().register();
  }

  @DgsSubscription
  public Publisher<Library> libraryAdded() {
    requireSubscriptionProfile();
    return broadcasters.getAdditions().register();
  }

  @DgsSubscription
  public Publisher<RemovedLibrary> libraryRemoved() {
    requireSubscriptionProfile();
    return broadcasters.getRemovedLibraries().register();
  }

  private void requireSubscriptionProfile() {
    libraryQueries.requireSubscriptionProfile(authorizationService.currentIdentity());
  }
}
