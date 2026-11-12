package com.streamarr.server.services.events.notification;

import com.streamarr.server.domain.notification.ProfileShareAnswer;
import lombok.NonNull;

/** What the offering side was told of an answer: the Profile's name when it was answered. */
public record ProfileShareAnsweredSnapshot(
    @NonNull String profileName, @NonNull ProfileShareAnswer answer)
    implements NotificationSnapshot {}
