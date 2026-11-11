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
  private final NotificationResolver notificationResolver;
  private final KeyedSubscriptionBroadcaster<UUID, NotificationsChange>
      notificationsChangeBroadcaster;

  // The Account comes from the token that opened the socket, never from an argument. Each change
  // is decided as it is delivered: one whose Notifications the guard all hides is not delivered.
  @DgsSubscription
  public Publisher<NotificationsChangeDetails> notificationsChanged() {
    var identity = authorizationService.currentIdentity();
    notificationService.requireViewNotifications(identity);
    return notificationsChangeBroadcaster
        .register(identity.accountId())
        .<NotificationsChangeDetails>handle(
            (change, sink) ->
                notificationService
                    .shownChange(identity, change.notificationIds())
                    .map(notificationResolver::changeDetails)
                    .ifPresent(sink::next));
  }
}
