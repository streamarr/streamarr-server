package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsSubscription;
import com.streamarr.server.graphql.dto.NotificationsChange;
import com.streamarr.server.graphql.dto.NotificationsChangeDetails;
import com.streamarr.server.graphql.subscriptions.KeyedSubscriptionBroadcaster;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.notification.NotificationService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;

@DgsComponent
@RequiredArgsConstructor
public class NotificationSubscriptionResolver {

  private final AuthorizationService authorizationService;
  private final NotificationService notificationService;
  private final KeyedSubscriptionBroadcaster<UUID, NotificationsChange>
      notificationsChangeBroadcaster;

  // The Account comes from the token that opened the socket, never from an argument. Access is
  // decided once, when the field opens. The stream carries only the ids of each change; its fields
  // read the Account's own rows when the change is delivered, so a read that fails is an error at
  // that field alone and the operation goes on.
  @DgsSubscription
  public Publisher<NotificationsChangeDetails> notificationsChanged() {
    var identity = authorizationService.currentIdentity();
    notificationService.requireViewNotifications(identity);
    return notificationsChangeBroadcaster
        .register(identity.accountId())
        .map(change -> new NotificationsChangeDetails(identity, change.notificationIds()));
  }
}
