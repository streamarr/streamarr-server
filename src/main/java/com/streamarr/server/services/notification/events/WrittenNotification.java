package com.streamarr.server.services.notification.events;

import java.util.UUID;
import lombok.NonNull;

/** One Notification an event made or updated in place, and the Account it tells. */
public record WrittenNotification(
    @NonNull UUID notificationId, @NonNull UUID recipientAccountId, @NonNull Write write) {

  public enum Write {
    /** The event made the Notification. */
    MADE,
    /** The event updated the recipient's unread news of its kind in place. */
    UPDATED
  }
}
