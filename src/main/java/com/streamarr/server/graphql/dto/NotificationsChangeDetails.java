package com.streamarr.server.graphql.dto;

import java.util.List;
import java.util.UUID;

/** A Notifications change as the schema shows it. */
public record NotificationsChangeDetails(
    List<NotificationChangeDetails> notifications, Integer unreadNotificationCount) {

  /** One Notification of the change, at its place in the list. */
  public record NotificationChangeDetails(
      UUID notificationId, OrderedEdge<NotificationDetails> edge) {}
}
