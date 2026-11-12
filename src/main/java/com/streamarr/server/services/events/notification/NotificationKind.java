package com.streamarr.server.services.events.notification;

import com.streamarr.server.domain.notification.NotificationForm;
import lombok.Builder;
import lombok.NonNull;

/**
 * What happened (ADR 0063 §The kinds). The kind fixes the Notification's form, the type of its
 * subject, the record type of its snapshot and, for news, how a later event updates the unread
 * news, and a Notification keeps its kind for its whole life. A new kind needs no column and no
 * migration.
 */
@Builder
public record NotificationKind(
    @NonNull String name,
    @NonNull NotificationForm form,
    @NonNull String subjectType,
    @NonNull Class<? extends NotificationSnapshot> snapshotType,
    @NonNull NewsUpdate newsUpdate) {

  /**
   * @throws IllegalArgumentException when the subject is not of this kind's subject type
   */
  void requireAbout(SubjectReference subject) {
    if (!subjectType.equals(subject.type())) {
      throw new IllegalArgumentException(
          "A %s Notification is about a %s, not a %s".formatted(name, subjectType, subject.type()));
    }
  }

  /**
   * @throws IllegalArgumentException when the snapshot is not of this kind's record type
   */
  void requireSnapshot(NotificationSnapshot snapshot) {
    if (!snapshotType.isInstance(snapshot)) {
      throw new IllegalArgumentException(
          "A %s Notification keeps a %s, not a %s"
              .formatted(name, snapshotType.getSimpleName(), snapshot.getClass().getSimpleName()));
    }
  }

  /** A kind's later news replaces the stored snapshot unless the kind says otherwise. */
  public static class NotificationKindBuilder {
    private NewsUpdate newsUpdate = NewsUpdate.REPLACE;
  }
}
