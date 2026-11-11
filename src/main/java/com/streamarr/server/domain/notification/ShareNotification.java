package com.streamarr.server.domain.notification;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import java.time.Instant;
import java.util.Optional;
import lombok.NonNull;

/**
 * A Notification with the share it is about, as its recipient reads it at one instant (ADR 0063
 * §Requests and news). News stays unread until its recipient reads it. A request also reads as read
 * once it resolves, and its resolution comes from the share, never from the Notification.
 */
public record ShareNotification(
    @NonNull Notification notification, @NonNull ProfileHouseholdShare share) {

  public boolean unreadAt(Instant now) {
    return notification.getReadAt().isEmpty() && resolutionAt(now).isEmpty();
  }

  /** How the request resolved at the instant; empty while it waits, and always for news. */
  public Optional<RequestResolution> resolutionAt(Instant now) {
    return switch (notification.getForm()) {
      case REQUEST -> RequestResolution.of(share, now);
      case NEWS -> Optional.empty();
    };
  }
}
