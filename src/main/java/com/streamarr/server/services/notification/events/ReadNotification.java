package com.streamarr.server.services.notification.events;

import java.util.UUID;
import lombok.NonNull;

/** One Notification that became read, and the Account it tells. */
public record ReadNotification(
    @NonNull UUID notificationId, @NonNull UUID recipientAccountId, @NonNull Read read) {

  public enum Read {
    /** Its recipient marked it read. */
    MARKED,
    /** It is a request, and its subject stopped waiting. */
    RESOLVED
  }
}
