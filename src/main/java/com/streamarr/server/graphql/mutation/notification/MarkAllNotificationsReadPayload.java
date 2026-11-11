package com.streamarr.server.graphql.mutation.notification;

import java.util.List;
import java.util.Optional;

public record MarkAllNotificationsReadPayload(
    Integer unreadNotificationCount, List<MarkAllNotificationsReadError> userErrors) {

  /** The envelope of a mark: the Account's unread count after it, or the errors. */
  public static MarkAllNotificationsReadPayload of(
      Optional<Integer> unreadNotificationCount, List<MarkAllNotificationsReadError> userErrors) {
    return new MarkAllNotificationsReadPayload(unreadNotificationCount.orElse(null), userErrors);
  }
}
