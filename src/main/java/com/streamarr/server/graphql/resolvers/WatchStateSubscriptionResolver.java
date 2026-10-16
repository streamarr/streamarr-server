package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsSubscription;
import com.streamarr.server.graphql.dto.WatchStateChange;
import com.streamarr.server.graphql.subscriptions.KeyedSubscriptionBroadcaster;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;

@DgsComponent
@RequiredArgsConstructor
public class WatchStateSubscriptionResolver {

  private final AuthorizationService authorizationService;
  private final WatchStatusService watchStatusService;
  private final KeyedSubscriptionBroadcaster<UUID, WatchStateChange> watchStateChangeBroadcaster;

  // The Profile comes from the token that opened the socket, never from an argument.
  @DgsSubscription
  public Publisher<WatchStateChange> watchStateChanged() {
    var identity = authorizationService.currentIdentity();
    watchStatusService.requireViewProfileActivity(identity);
    return watchStateChangeBroadcaster.register(identity.profileId());
  }
}
