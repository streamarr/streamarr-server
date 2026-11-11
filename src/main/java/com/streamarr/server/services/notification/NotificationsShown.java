package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.ShareNotification;
import com.streamarr.server.domain.pagination.Row;
import java.util.List;
import lombok.NonNull;

/**
 * The Notifications of one change that the guard shows their recipient, each at its place in the
 * list, and the recipient's unread count after the change.
 */
public record NotificationsShown(
    @NonNull List<Row<ShareNotification>> notifications, int unreadCount) {

  public NotificationsShown {
    notifications = List.copyOf(notifications);
  }
}
