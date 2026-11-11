package com.streamarr.server.graphql.dto;

import java.util.List;
import java.util.UUID;
import lombok.NonNull;

/**
 * The ids of the Notifications one committed change touched, for the operations of their recipient.
 * Each operation reads them as its Account may see them when it delivers the change.
 */
public record NotificationsChange(@NonNull List<UUID> notificationIds) {

  public NotificationsChange {
    notificationIds = List.copyOf(notificationIds);
  }
}
