package com.streamarr.server.domain.notification;

import java.util.UUID;
import lombok.NonNull;

/**
 * The recipient's one unread news of a kind about a subject, after an event of that kind (ADR 0063
 * §One unread Notification per kind, subject and recipient).
 *
 * @param <F> what the kind carries beyond its subject
 */
public sealed interface UnreadNews<F> {

  UUID notificationId();

  /** The event made the recipient's unread news; it carries the event's facts. */
  record Made<F>(@NonNull UUID notificationId) implements UnreadNews<F> {}

  /**
   * The recipient already had unread news of the kind about the subject: it took the event's time,
   * and it still carries the facts stored before the event.
   */
  record Moved<F>(@NonNull UUID notificationId, @NonNull F storedFacts) implements UnreadNews<F> {}
}
