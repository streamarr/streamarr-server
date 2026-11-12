package com.streamarr.server.services.events.notification;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import lombok.Builder;
import lombok.NonNull;

/**
 * Tells the recipients what happened to a subject (ADR 0063 §How the server builds it). The
 * publishing domain decided the recipients from the relationships as they stood at the event, and
 * left out the Account that acted. It is heard inside the publisher's transaction, so its
 * Notifications commit or roll back with the change that made them. A request carries its subject's
 * expiry; news carries none.
 */
@Builder
public record TellNotificationEvent(
    @NonNull NotificationKind kind,
    @NonNull SubjectReference subject,
    @NonNull Set<RecipientReference> recipients,
    @NonNull NotificationSnapshot snapshot,
    @NonNull Instant at,
    @NonNull Optional<Instant> subjectExpiresAt) {

  /**
   * @throws IllegalArgumentException when the subject or the snapshot is not of the kind's type, or
   *     when news carries an expiry
   */
  public TellNotificationEvent {
    kind.requireAbout(subject);
    kind.requireSnapshot(snapshot);
    if (kind.form() == NotificationForm.NEWS && subjectExpiresAt.isPresent()) {
      throw new IllegalArgumentException(
          "A %s Notification is news, which has no expiry".formatted(kind.name()));
    }

    recipients = Set.copyOf(recipients);
  }

  /** Leaves the subject's expiry empty unless the tell names one. */
  public static class TellNotificationEventBuilder {
    private Optional<Instant> subjectExpiresAt = Optional.empty();
  }
}
