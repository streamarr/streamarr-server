package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.graphql.dto.NotificationsChange;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.notification.events.ChangedNotification;
import com.streamarr.server.services.notification.events.NotificationsChangedEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Passes each committed change to Notifications to the operations of each recipient Account, one
 * change per recipient. It reads nothing: each operation reads the Notifications on a delivery
 * thread, after the writer has released its database connection.
 */
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

  private final KeyedSubscriptionBroadcaster<UUID, NotificationsChange>
      notificationsChangeBroadcaster;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onNotificationsChanged(NotificationsChangedEvent event) {
    event.notifications().stream()
        .filter(changed -> RecipientReference.ACCOUNT.equals(changed.recipient().type()))
        .collect(
            Collectors.groupingBy(
                changed -> changed.recipient().id(),
                LinkedHashMap::new,
                Collectors.mapping(ChangedNotification::notificationId, Collectors.toList())))
        .forEach(this::broadcastTo);
  }

  private void broadcastTo(UUID recipientAccountId, List<UUID> notificationIds) {
    notificationsChangeBroadcaster.broadcast(
        recipientAccountId, new NotificationsChange(notificationIds.stream().distinct().toList()));
  }
}
