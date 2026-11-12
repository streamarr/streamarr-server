package com.streamarr.server.services.notification.events;

import com.streamarr.server.services.events.notification.RecipientReference;
import java.util.UUID;
import lombok.NonNull;

/** One Notification that an event made, changed or dismissed, and the recipient it tells. */
public record ChangedNotification(
    @NonNull UUID notificationId, @NonNull RecipientReference recipient) {}
