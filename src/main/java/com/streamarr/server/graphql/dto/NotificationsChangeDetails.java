package com.streamarr.server.graphql.dto;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;

/**
 * One committed change to Notifications as the schema shows it to one operation: the ids it
 * touched, each once, and the session that follows them. Each field is read when the change is
 * delivered, on its own, so a read that fails nulls that field alone.
 */
public record NotificationsChangeDetails(
    @NonNull AuthenticatedIdentity reader, @NonNull List<UUID> notificationIds) {

  public NotificationsChangeDetails {
    notificationIds = notificationIds.stream().distinct().toList();
  }

  public List<NotificationChangeDetails> notifications() {
    return notificationIds.stream().map(id -> new NotificationChangeDetails(reader, id)).toList();
  }

  /** One Notification of the change, read at its place in the list when it is delivered. */
  public record NotificationChangeDetails(
      @NonNull AuthenticatedIdentity reader, @NonNull UUID notificationId) {}
}
