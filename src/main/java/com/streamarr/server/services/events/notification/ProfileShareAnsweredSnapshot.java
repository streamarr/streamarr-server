package com.streamarr.server.services.events.notification;

import com.streamarr.server.domain.notification.NotificationSnapshot;
import com.streamarr.server.domain.notification.RequestResolution;
import java.util.Optional;
import lombok.NonNull;

/** What the offering side was told of an answer: the Profile's name when it was answered. */
public record ProfileShareAnsweredSnapshot(
    @NonNull String profileName, @NonNull ProfileShareAnswer answer)
    implements NotificationSnapshot {

  @Override
  public String line(Optional<RequestResolution> resolution) {
    return "The offer of %s was %s."
        .formatted(NotificationLines.quoted(profileName), NotificationLines.answered(answer));
  }
}
