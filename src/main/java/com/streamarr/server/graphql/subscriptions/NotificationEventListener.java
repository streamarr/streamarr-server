package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.graphql.dto.NotificationsChange;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.notification.events.ChangedNotification;
import com.streamarr.server.services.notification.events.NotificationsChangedEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Passes each committed change to Notifications to the operations of each recipient Account (ADR
 * 0063 §Live changes). A transaction can change one Account's Notifications several times, as when
 * deleting a Profile dismisses what was said about the Profile and about each of its shares, so the
 * listener gathers what the transaction changed and, once it commits, pushes one change to each
 * recipient with each of its Notifications once. A transaction that rolls back pushes nothing, and
 * so does a change announced with no transaction behind it. It reads nothing: each operation reads
 * the Notifications on a delivery thread, after the writer has released its database connection.
 */
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

  private final KeyedSubscriptionBroadcaster<UUID, NotificationsChange>
      notificationsChangeBroadcaster;

  @EventListener
  public void onNotificationsChanged(NotificationsChangedEvent event) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }

    pendingPush().gather(event.notifications());
  }

  private PendingPush pendingPush() {
    if (TransactionSynchronizationManager.getResource(this) instanceof PendingPush pending) {
      return pending;
    }

    var pending = new PendingPush();
    TransactionSynchronizationManager.bindResource(this, pending);
    TransactionSynchronizationManager.registerSynchronization(pending);
    return pending;
  }

  private void push(List<ChangedNotification> changed) {
    changed.stream()
        .filter(notification -> RecipientReference.ACCOUNT.equals(notification.recipient().type()))
        .collect(
            Collectors.groupingBy(
                notification -> notification.recipient().id(),
                LinkedHashMap::new,
                Collectors.mapping(
                    ChangedNotification::notificationId,
                    Collectors.toCollection(LinkedHashSet::new))))
        .forEach(this::broadcastTo);
  }

  private void broadcastTo(UUID recipientAccountId, Set<UUID> notificationIds) {
    notificationsChangeBroadcaster.broadcast(
        recipientAccountId, new NotificationsChange(List.copyOf(notificationIds)));
  }

  /**
   * What one transaction changed, bound to it while it runs. A transaction that suspends it for an
   * inner one takes it back when it resumes, so each transaction pushes only what it committed.
   */
  private final class PendingPush implements TransactionSynchronization {

    private final List<ChangedNotification> changed = new ArrayList<>();

    void gather(List<ChangedNotification> notifications) {
      changed.addAll(notifications);
    }

    @Override
    public void suspend() {
      TransactionSynchronizationManager.unbindResource(NotificationEventListener.this);
    }

    @Override
    public void resume() {
      TransactionSynchronizationManager.bindResource(NotificationEventListener.this, this);
    }

    @Override
    public void afterCommit() {
      push(changed);
    }

    @Override
    public void afterCompletion(int status) {
      TransactionSynchronizationManager.unbindResourceIfPossible(NotificationEventListener.this);
    }
  }
}
