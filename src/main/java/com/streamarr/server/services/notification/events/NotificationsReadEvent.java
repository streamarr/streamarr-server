package com.streamarr.server.services.notification.events;

import java.util.List;
import lombok.NonNull;

/**
 * Notifications became read: their recipient marked them, or requests resolved with their subject.
 * It is published inside the transaction of the change, so a listener after commit hears only reads
 * that committed.
 */
public record NotificationsReadEvent(@NonNull List<ReadNotification> notifications) {

  public NotificationsReadEvent {
    notifications = List.copyOf(notifications);
  }
}
