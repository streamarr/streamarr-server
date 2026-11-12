package com.streamarr.server.services.notification;

import com.streamarr.server.services.events.notification.NotificationKind;
import com.streamarr.server.services.events.notification.NotificationSnapshot;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.events.notification.RequestResolution;
import com.streamarr.server.services.events.notification.SubjectReference;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * One recipient's Notification as the module keeps it (ADR 0063 §What a Notification stores). It
 * reads from its own data alone: a request whose stored expiry has passed reads as expired before
 * any event says so.
 */
@Builder
public record StoredNotification(
    @NonNull UUID id,
    @NonNull RecipientReference recipient,
    @NonNull NotificationKind kind,
    @NonNull SubjectReference subject,
    @NonNull NotificationSnapshot snapshot,
    @NonNull Instant createdAt,
    @NonNull Instant latestEventAt,
    @NonNull Optional<Instant> readAt,
    @NonNull Optional<RequestResolution> resolution,
    @NonNull Optional<Instant> resolvedAt,
    @NonNull Optional<Instant> expiresAt) {

  /**
   * How the request reads at the instant: as an event resolved it, or expired once its expiry
   * passed.
   */
  public Optional<RequestResolution> resolutionAt(Instant now) {
    if (resolution.isPresent()) {
      return resolution;
    }

    return expiredAt(now).map(_ -> RequestResolution.EXPIRED);
  }

  /** When the request resolved, as it reads at the instant: its expiry once it reads as expired. */
  public Optional<Instant> resolutionTimeAt(Instant now) {
    if (resolvedAt.isPresent()) {
      return resolvedAt;
    }

    return expiredAt(now);
  }

  /** True until its recipient marks it read; a request is also read once it reads as resolved. */
  public boolean unreadAt(Instant now) {
    return readAt.isEmpty() && resolutionAt(now).isEmpty();
  }

  /** The line its recipient reads at the instant. */
  public String lineAt(Instant now) {
    return snapshot.line(resolutionAt(now));
  }

  private Optional<Instant> expiredAt(Instant now) {
    return expiresAt.filter(expiry -> !expiry.isAfter(now));
  }
}
