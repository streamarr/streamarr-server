package com.streamarr.server.domain.notification;

import java.time.Instant;

/**
 * A stored Notification with the subject it is about, as its recipient reads it (ADR 0063). There
 * is one type for each {@link NotificationSubject}.
 */
public sealed interface SubjectNotification permits ShareNotification {

  Notification notification();

  /**
   * Whether the recipient has it unread at the instant: news until its recipient reads it, and a
   * request also until it resolves.
   */
  boolean unreadAt(Instant now);

  /** The same subject with the Notification as it is stored now. */
  SubjectNotification withNotification(Notification stored);
}
