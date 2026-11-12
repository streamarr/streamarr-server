package com.streamarr.server.services.notification;

import com.streamarr.server.domain.pagination.Row;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;

/**
 * One committed change to an Account's Notifications as the Account reads it when the change is
 * delivered: each Notification the change touched, at its place in the list or gone, and the
 * Account's unread count after it.
 */
public record NotificationsChangeRead(@NonNull List<Changed> notifications, int unreadCount) {

  public NotificationsChangeRead {
    notifications = List.copyOf(notifications);
  }

  /** One Notification of the change: its row, or empty once it no longer exists. */
  public record Changed(
      @NonNull UUID notificationId, @NonNull Optional<Row<StoredNotification>> row) {}
}
