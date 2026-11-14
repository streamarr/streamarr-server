package com.streamarr.server.services.events.notification;

import com.streamarr.server.domain.notification.NotificationSnapshot;
import com.streamarr.server.domain.notification.RequestResolution;
import java.util.Optional;
import lombok.NonNull;

/**
 * What the invited Account was told of an invitation to manage a Profile: the Profile's name when
 * the invitation was issued, and the answer once the Account decides it, which its resolution
 * writes. It keeps neither the invitation's code nor the inviter's name.
 */
public record ProfileManagerInvitationReceivedSnapshot(
    @NonNull String profileName, @NonNull Optional<ProfileManagerInvitationAnswer> answer)
    implements NotificationSnapshot {

  @Override
  public String line(Optional<RequestResolution> resolution) {
    var profile = NotificationLines.quoted(profileName);
    var invitation = "The invitation to manage %s".formatted(profile);
    return resolution
        .map(
            resolved ->
                NotificationLines.resolved(
                    invitation, resolved, answer.map(NotificationLines::answered)))
        .orElse("You were invited to manage %s.".formatted(profile));
  }
}
