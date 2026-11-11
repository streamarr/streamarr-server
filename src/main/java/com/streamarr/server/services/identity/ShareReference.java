package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;

/**
 * The parties a share names: the Profile, the Household it is into, and the Account that offered
 * it, which is absent for a share the system made and once that Account is deleted. {@code live}
 * says whether the offer was still pending or the share active when it was read.
 */
public record ShareReference(
    @NonNull UUID profileId,
    @NonNull UUID householdId,
    @NonNull Optional<UUID> offeredBy,
    boolean live) {

  /** The share's parties as it stands at {@code now}; an offer past its expiry is not live. */
  public static ShareReference of(ProfileHouseholdShare share, Instant now) {
    return new ShareReference(
        share.getProfileId(),
        share.getHouseholdId(),
        Optional.ofNullable(share.getOfferedByAccountId()),
        share.statusAt(now).isLive());
  }
}
