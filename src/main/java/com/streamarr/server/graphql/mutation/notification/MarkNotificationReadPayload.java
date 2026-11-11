package com.streamarr.server.graphql.mutation.notification;

import com.streamarr.server.graphql.dto.NotificationDetails;
import java.util.List;
import java.util.Optional;

public record MarkNotificationReadPayload(
    NotificationDetails notification,
    Integer unreadNotificationCount,
    List<MarkNotificationReadError> userErrors) {

  /** The envelope of a mark: the marked Notification and the count after it, or the errors. */
  public static MarkNotificationReadPayload of(
      Optional<MarkedNotification> marked, List<MarkNotificationReadError> userErrors) {
    return new MarkNotificationReadPayload(
        marked.map(MarkedNotification::notification).orElse(null),
        marked.map(MarkedNotification::unreadNotificationCount).orElse(null),
        userErrors);
  }

  /** A marked Notification, as the schema shows it, and the Account's unread count after it. */
  public record MarkedNotification(NotificationDetails notification, int unreadNotificationCount) {}
}
