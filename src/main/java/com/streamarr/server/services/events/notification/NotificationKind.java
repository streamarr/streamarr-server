package com.streamarr.server.services.events.notification;

import com.streamarr.server.domain.notification.NotificationForm;
import lombok.Builder;
import lombok.NonNull;

/**
 * What happened (ADR 0063 §The kinds). The kind fixes the Notification's form, the type of its
 * subject and the record type of its snapshot, and a Notification keeps its kind for its whole
 * life. A new kind needs no column and no migration.
 */
@Builder
public record NotificationKind(
    @NonNull String name,
    @NonNull NotificationForm form,
    @NonNull String subjectType,
    @NonNull Class<? extends NotificationSnapshot> snapshotType) {}
