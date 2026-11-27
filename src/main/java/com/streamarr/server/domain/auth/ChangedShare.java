package com.streamarr.server.domain.auth;

import java.util.UUID;
import lombok.NonNull;

/**
 * A share that one write changed, with the Profile it shares, the Household it is into, and the
 * status the write left it in. A share announced without being written, as it is moved or deleted,
 * carries the status it is stored with, except that a deleted offer whose time ran out carries
 * EXPIRED.
 */
public record ChangedShare(
    @NonNull UUID shareId,
    @NonNull UUID profileId,
    @NonNull UUID householdId,
    @NonNull ProfileShareStatus status) {

  public static ChangedShare of(ProfileHouseholdShare share) {
    return new ChangedShare(
        share.getId(), share.getProfileId(), share.getHouseholdId(), share.getStatus());
  }
}
