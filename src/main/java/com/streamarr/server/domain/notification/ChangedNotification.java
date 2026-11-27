package com.streamarr.server.domain.notification;

import java.util.UUID;
import lombok.NonNull;

/** One Notification that an event made, changed or dismissed, and the recipient it tells. */
public record ChangedNotification(
    @NonNull UUID notificationId, @NonNull RecipientReference recipient) {}
