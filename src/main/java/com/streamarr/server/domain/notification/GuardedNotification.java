package com.streamarr.server.domain.notification;

import java.util.UUID;
import lombok.NonNull;

/**
 * A Notification about a share, with what its guard decides on for its recipient: the share's
 * Profile and Household.
 */
public record GuardedNotification(
    @NonNull UUID notificationId,
    @NonNull UUID recipientAccountId,
    @NonNull UUID profileId,
    @NonNull UUID householdId) {}
