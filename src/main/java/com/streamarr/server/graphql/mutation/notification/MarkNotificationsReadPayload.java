package com.streamarr.server.graphql.mutation.notification;

import java.util.List;
import java.util.Optional;

public record MarkNotificationsReadPayload(
    Integer unreadNotificationCount, List<MarkNotificationsReadError> userErrors) {

  /** The envelope of a mark: the Account's unread count after it, or the errors. */
  public static MarkNotificationsReadPayload of(
      Optional<Integer> unreadNotificationCount, List<MarkNotificationsReadError> userErrors) {
    return new MarkNotificationsReadPayload(unreadNotificationCount.orElse(null), userErrors);
  }
}
