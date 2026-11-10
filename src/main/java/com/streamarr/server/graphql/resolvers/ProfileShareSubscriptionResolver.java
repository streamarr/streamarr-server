package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsSubscription;
import com.streamarr.server.graphql.dto.ProfileShareChangeDetails;
import com.streamarr.server.graphql.subscriptions.SubscriptionBroadcaster;
import com.streamarr.server.services.auth.events.ProfileShareChangedEvent;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.ProfileSharingService;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;

@DgsComponent
@RequiredArgsConstructor
public class ProfileShareSubscriptionResolver {

  private final AuthorizationService authorizationService;
  private final ProfileSharingService profileSharingService;
  private final SubscriptionBroadcaster<ProfileShareChangedEvent> profileShareChangeBroadcaster;
  private final Clock clock;

  // Each change is decided again as it is delivered, so authority that ends stops the changes,
  // and an Account that may no longer follow share changes ends the operation with the error.
  @DgsSubscription
  public Publisher<ProfileShareChangeDetails> profileShareChanged() {
    var identity = authorizationService.currentIdentity();
    profileSharingService.requireViewProfileShareChanges(identity);
    return profileShareChangeBroadcaster
        .register()
        .<ProfileShareChangeDetails>handle(
            (event, sink) ->
                profileSharingService
                    .shareChange(identity, event.share())
                    .map(change -> ProfileShareChangeDetails.from(change, clock.instant()))
                    .ifPresent(sink::next));
  }
}
