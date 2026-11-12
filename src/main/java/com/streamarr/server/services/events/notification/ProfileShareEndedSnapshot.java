package com.streamarr.server.services.events.notification;

import com.streamarr.server.domain.notification.ProfileShareEndedBy;
import com.streamarr.server.domain.notification.RequestResolution;
import java.util.Optional;
import lombok.NonNull;

/** What a side was told of a share's end: which side ended it, and the Profile's name then. */
public record ProfileShareEndedSnapshot(
    @NonNull String profileName, @NonNull ProfileShareEndedBy endedBy)
    implements NotificationSnapshot {

  @Override
  public String line(Optional<RequestResolution> resolution) {
    var profile = NotificationLines.quoted(profileName);
    return switch (endedBy) {
      case HOUSEHOLD_SIDE -> "A Household stopped sharing %s.".formatted(profile);
      case OFFERING_SIDE -> "%s is no longer shared with your Household.".formatted(profile);
      case SERVER -> "The share of %s was ended.".formatted(profile);
    };
  }
}
