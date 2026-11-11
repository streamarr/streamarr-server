package com.streamarr.server.domain.notification;

import java.util.UUID;
import lombok.NonNull;

/** Unread news about a share, with what its guard decides on for its recipient. */
public record UnreadShareNews(
    @NonNull UUID notificationId,
    @NonNull UUID recipientAccountId,
    @NonNull UUID profileId,
    @NonNull UUID householdId) {}
