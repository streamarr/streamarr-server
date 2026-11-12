package com.streamarr.server.services.events.notification;

import com.streamarr.server.domain.notification.ProfileShareEndedBy;
import lombok.NonNull;

/** What a side was told of a share's end: which side ended it, and the Profile's name then. */
public record ProfileShareEndedSnapshot(
    @NonNull String profileName, @NonNull ProfileShareEndedBy endedBy)
    implements NotificationSnapshot {}
