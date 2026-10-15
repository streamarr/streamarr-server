package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsSubscription;
import com.streamarr.server.domain.Library;
import com.streamarr.server.graphql.dto.LibraryRemoval;
import com.streamarr.server.graphql.subscriptions.LibraryAdministrationBroadcasters;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.library.LibraryAdministrationService;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;

@DgsComponent
@RequiredArgsConstructor
public class LibraryAdministrationSubscriptionResolver {

  private final AuthorizationService authorizationService;
  private final LibraryAdministrationService libraryAdministrationService;
  private final LibraryAdministrationBroadcasters broadcasters;

  @DgsSubscription
  public Publisher<Library> libraryStatusChanged() {
    requireViewLibraryAdministration();
    return broadcasters.getStatusChanges().register();
  }

  @DgsSubscription
  public Publisher<Library> libraryAdded() {
    requireViewLibraryAdministration();
    return broadcasters.getAdditions().register();
  }

  @DgsSubscription
  public Publisher<LibraryRemoval> libraryRemoved() {
    requireViewLibraryAdministration();
    return broadcasters.getRemovals().register();
  }

  private void requireViewLibraryAdministration() {
    libraryAdministrationService.requireViewLibraryAdministration(
        authorizationService.currentIdentity());
  }
}
