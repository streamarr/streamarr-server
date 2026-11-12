package com.streamarr.server.domain.notification;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A Notification about a share, with what its guard decides on for its recipient (ADR 0063 §The
 * sharing kinds): the share's Profile and Household, whether the offer was pending and not expired
 * or the share active when it was read, and the side of the share that the Notification was
 * addressed to.
 */
@Builder
public record GuardedNotification(
    @NonNull UUID notificationId,
    @NonNull UUID recipientAccountId,
    @NonNull UUID profileId,
    @NonNull UUID householdId,
    boolean shareLive,
    @NonNull ProfileShareSide addressedTo) {}
