package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;

/**
 * The parties a share names: the Profile, the Household it is into, and the Account that offered
 * it, which is absent for a share the system made and once that Account is deleted.
 */
public record ShareReference(
    @NonNull UUID profileId, @NonNull UUID householdId, @NonNull Optional<UUID> offeredBy) {

  public static ShareReference of(ProfileHouseholdShare share) {
    return new ShareReference(
        share.getProfileId(),
        share.getHouseholdId(),
        Optional.ofNullable(share.getOfferedByAccountId()));
  }
}
