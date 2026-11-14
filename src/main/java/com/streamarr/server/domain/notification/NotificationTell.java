package com.streamarr.server.domain.notification;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * What a tell stores for each recipient (ADR 0063 §How the server builds it): a Notification of the
 * kind about the subject, with the snapshot, the event's time and, for a request, the subject's
 * expiry.
 */
public interface NotificationTell {

  NotificationKind kind();

  SubjectReference subject();

  Set<RecipientReference> recipients();

  NotificationSnapshot snapshot();

  Instant at();

  Optional<Instant> subjectExpiresAt();
}
