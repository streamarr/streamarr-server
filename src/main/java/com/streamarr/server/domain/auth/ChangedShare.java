package com.streamarr.server.domain.auth;

import java.util.UUID;
import lombok.NonNull;

/** A share that one write changed, with the Profile it shares and the Household it is into. */
public record ChangedShare(
    @NonNull UUID shareId, @NonNull UUID profileId, @NonNull UUID householdId) {

  public static ChangedShare of(ProfileHouseholdShare share) {
    return new ChangedShare(share.getId(), share.getProfileId(), share.getHouseholdId());
  }
}
