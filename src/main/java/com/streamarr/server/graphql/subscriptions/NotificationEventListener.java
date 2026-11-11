package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.graphql.dto.NotificationsChange;
import com.streamarr.server.services.notification.events.NotificationsReadEvent;
import com.streamarr.server.services.notification.events.NotificationsWrittenEvent;
import com.streamarr.server.services.notification.events.ReadNotification;
import com.streamarr.server.services.notification.events.WrittenNotification;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Passes each committed change to Notifications to the operations of each recipient, one change per
 * recipient. It reads nothing: each operation reads the Notifications as its Account may see them
 * on a delivery thread, after the writer has released its database connection.
 */
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

  private final KeyedSubscriptionBroadcaster<UUID, NotificationsChange>
      notificationsChangeBroadcaster;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onNotificationsWritten(NotificationsWrittenEvent event) {
    broadcast(
        event.notifications().stream(),
        WrittenNotification::recipientAccountId,
        WrittenNotification::notificationId);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onNotificationsRead(NotificationsReadEvent event) {
    broadcast(
        event.notifications().stream(),
        ReadNotification::recipientAccountId,
        ReadNotification::notificationId);
  }

  private <N> void broadcast(
      Stream<N> notifications, Function<N, UUID> recipient, Function<N, UUID> notificationId) {
    notifications
        .collect(
            Collectors.groupingBy(
                recipient,
                LinkedHashMap::new,
                Collectors.mapping(notificationId, Collectors.toList())))
        .forEach(this::broadcastTo);
  }

  private void broadcastTo(UUID recipientAccountId, List<UUID> notificationIds) {
    notificationsChangeBroadcaster.broadcast(
        recipientAccountId, new NotificationsChange(notificationIds.stream().distinct().toList()));
  }
}
