package com.streamarr.server.services.notification.events;

import java.util.List;
import lombok.NonNull;

/**
 * Notifications were made or updated in place by one event. It is published inside the transaction
 * of the change that caused the event, so a listener after commit hears only Notifications that
 * committed. Live changes and any later channel, such as email, start here.
 */
public record NotificationsWrittenEvent(@NonNull List<WrittenNotification> notifications) {

  public NotificationsWrittenEvent {
    notifications = List.copyOf(notifications);
  }
}
