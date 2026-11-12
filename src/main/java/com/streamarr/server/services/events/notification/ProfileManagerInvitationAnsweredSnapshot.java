package com.streamarr.server.services.events.notification;

import com.streamarr.server.domain.notification.NotificationSnapshot;
import com.streamarr.server.domain.notification.RequestResolution;
import java.util.Optional;
import lombok.NonNull;

/**
 * What the inviter was told of the invited Account's answer: the answer, and the Profile's name
 * when the invitation was answered.
 */
public record ProfileManagerInvitationAnsweredSnapshot(
    @NonNull String profileName, @NonNull ProfileManagerInvitationAnswer answer)
    implements NotificationSnapshot {

  @Override
  public String line(Optional<RequestResolution> resolution) {
    return "Your invitation to manage %s was %s."
        .formatted(NotificationLines.quoted(profileName), NotificationLines.answered(answer));
  }
}
