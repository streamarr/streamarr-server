package com.streamarr.server.services.events.notification;

import java.time.Instant;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * Settles every request of a kind about a subject that no event has resolved yet, for every
 * recipient at once (ADR 0063 §Requests and news). The subject's domain publishes it inside the
 * transaction that changes the subject, with the instant of that change: the instant it records on
 * the subject and checks the subject's expiry against. A decision carries its answer.
 */
@Builder
public record ResolveRequestsEvent(
    @NonNull NotificationKind kind,
    @NonNull SubjectReference subject,
    @NonNull RequestResolution resolution,
    @NonNull Optional<RequestAnswer> answer,
    @NonNull Instant at) {

  /**
   * @throws IllegalArgumentException when the kind is news, when the subject is not of the kind's
   *     type, or when a resolution other than a decision carries an answer
   */
  public ResolveRequestsEvent {
    if (kind.form() != NotificationForm.REQUEST) {
      throw new IllegalArgumentException(
          "A %s Notification is news, which never resolves".formatted(kind.name()));
    }

    kind.requireAbout(subject);
    if (answer.isPresent() && resolution != RequestResolution.DECIDED) {
      throw new IllegalArgumentException(
          "Only a decision carries an answer, not a %s resolution".formatted(resolution));
    }
  }

  /** Leaves the answer empty unless the resolution names one. */
  public static class ResolveRequestsEventBuilder {
    private Optional<RequestAnswer> answer = Optional.empty();
  }
}
