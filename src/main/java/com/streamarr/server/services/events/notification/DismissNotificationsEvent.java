package com.streamarr.server.services.events.notification;

import com.streamarr.server.domain.notification.DismissalSelector;
import lombok.NonNull;

/**
 * Deletes the Notifications a selector selects, for good (ADR 0063 §Dismissal). It is heard inside
 * the publisher's transaction, so the dismissal commits or rolls back with the event that caused
 * it.
 */
public record DismissNotificationsEvent(@NonNull DismissalSelector selector) {}
