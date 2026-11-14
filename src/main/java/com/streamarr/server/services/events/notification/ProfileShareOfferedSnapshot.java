package com.streamarr.server.services.events.notification;

import com.streamarr.server.domain.notification.NotificationSnapshot;
import com.streamarr.server.domain.notification.RequestResolution;
import java.util.Optional;
import lombok.NonNull;

/**
 * What a HouseholdAdmin was told of an offer: the Profile's name when it was offered, and the
 * answer once the offer is decided, which its resolution writes.
 */
public record ProfileShareOfferedSnapshot(
    @NonNull String profileName, @NonNull Optional<ProfileShareAnswer> answer)
    implements NotificationSnapshot {

  @Override
  public String line(Optional<RequestResolution> resolution) {
    var profile = NotificationLines.quoted(profileName);
    var offer = "The offer of %s to your Household".formatted(profile);
    return resolution
        .map(
            resolved ->
                NotificationLines.resolved(
                    offer, resolved, answer.map(NotificationLines::answered)))
        .orElse("%s was offered to your Household.".formatted(profile));
  }
}
