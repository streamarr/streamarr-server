package com.streamarr.server.domain.notification;

import java.util.Optional;
import lombok.NonNull;

/**
 * What a sharing Notification carries that its share does not keep (ADR 0063 §What a Notification
 * carries): the Profile's name at the latest event, and the answer or the side that ended the
 * share. The kind says which of the two it carries.
 */
public record ProfileShareFacts(
    @NonNull String profileName,
    @NonNull Optional<ProfileShareAnswer> answer,
    @NonNull Optional<ProfileShareEndedBy> endedBy) {

  public static ProfileShareFacts offered(String profileName) {
    return new ProfileShareFacts(profileName, Optional.empty(), Optional.empty());
  }

  public static ProfileShareFacts answered(String profileName, ProfileShareAnswer answer) {
    return new ProfileShareFacts(profileName, Optional.of(answer), Optional.empty());
  }

  public static ProfileShareFacts ended(String profileName, ProfileShareEndedBy endedBy) {
    return new ProfileShareFacts(profileName, Optional.empty(), Optional.of(endedBy));
  }
}
