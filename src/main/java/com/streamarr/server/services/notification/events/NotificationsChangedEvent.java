package com.streamarr.server.services.notification.events;

import java.util.List;
import lombok.NonNull;

/**
 * Notifications that one module operation made, changed or dismissed (ADR 0063 §Live changes). It
 * is published inside the transaction of the event that caused it, so a listener after commit hears
 * only changes that committed.
 */
public record NotificationsChangedEvent(@NonNull List<ChangedNotification> notifications) {

  public NotificationsChangedEvent {
    notifications = List.copyOf(notifications);
  }
}
