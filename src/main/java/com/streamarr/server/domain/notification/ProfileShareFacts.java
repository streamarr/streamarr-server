package com.streamarr.server.domain.notification;

import java.util.Optional;
import lombok.NonNull;

/**
 * What a sharing Notification carries that its share does not keep (ADR 0063 §What a Notification
 * carries): the Profile's name at the latest event, the answer or the side that ended the share,
 * and the side of the share that the Notification was addressed to. The kind says which of the
 * answer and the ender it carries, and which side it is addressed to, except for Share ended, which
 * tells the side that did not end the share, or each side when both are told.
 */
public record ProfileShareFacts(
    @NonNull String profileName,
    @NonNull Optional<ProfileShareAnswer> answer,
    @NonNull Optional<ProfileShareEndedBy> endedBy,
    @NonNull ProfileShareSide addressedTo) {

  public static ProfileShareFacts offered(String profileName) {
    return new ProfileShareFacts(
        profileName, Optional.empty(), Optional.empty(), ProfileShareSide.HOUSEHOLD_SIDE);
  }

  public static ProfileShareFacts answered(String profileName, ProfileShareAnswer answer) {
    return new ProfileShareFacts(
        profileName, Optional.of(answer), Optional.empty(), ProfileShareSide.OFFERING_SIDE);
  }

  public static ProfileShareFacts ended(
      String profileName, ProfileShareEndedBy endedBy, ProfileShareSide addressedTo) {
    return new ProfileShareFacts(profileName, Optional.empty(), Optional.of(endedBy), addressedTo);
  }

  /** These facts, addressed to the side that the stored ones were addressed to. */
  public ProfileShareFacts addressedAs(ProfileShareFacts stored) {
    return new ProfileShareFacts(profileName, answer, endedBy, stored.addressedTo());
  }
}
