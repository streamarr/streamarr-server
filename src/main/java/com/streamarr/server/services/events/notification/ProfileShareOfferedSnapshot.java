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
    return resolution
        .map(resolved -> resolvedLine(profile, resolved))
        .orElse("%s was offered to your Household.".formatted(profile));
  }

  private String resolvedLine(String profile, RequestResolution resolution) {
    var offer = "The offer of %s to your Household".formatted(profile);
    return switch (resolution) {
      case DECIDED ->
          "%s was %s.".formatted(offer, answer.map(NotificationLines::answered).orElse("decided"));
      case WITHDRAWN -> "%s was withdrawn.".formatted(offer);
      case EXPIRED -> "%s expired.".formatted(offer);
    };
  }
}
