package com.streamarr.server.services.events.notification;

import com.streamarr.server.domain.notification.NotificationSnapshot;
import com.streamarr.server.domain.notification.RequestResolution;
import java.util.Optional;
import lombok.NonNull;

/**
 * What an Account was told when someone else removed its management of a Profile: the Profile's
 * name at the removal, the name it saw until then.
 */
public record ProfileManagementRemovedSnapshot(@NonNull String profileName)
    implements NotificationSnapshot {

  @Override
  public String line(Optional<RequestResolution> resolution) {
    return "You no longer manage %s.".formatted(NotificationLines.quoted(profileName));
  }
}
