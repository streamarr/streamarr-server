package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.services.identity.ShareReference;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/**
 * A share as the schema shows it. {@code parties} is not a schema field: the share's Profile,
 * offerer and Household load from it, each for the readers who may see that party.
 */
@Builder
public record ProfileShareDetails(
    UUID id,
    UUID profileId,
    UUID householdId,
    ProfileShareStatus status,
    boolean requiredByAccountMembership,
    String expiresAt,
    String endedAt,
    String invalidationReason,
    ShareReference parties) {

  public static ProfileShareDetails from(ProfileHouseholdShare share, Instant now) {
    return ProfileShareDetails.builder()
        .id(share.getId())
        .profileId(share.getProfileId())
        .householdId(share.getHouseholdId())
        .status(share.statusAt(now))
        .requiredByAccountMembership(share.isStructural())
        .expiresAt(share.getExpiresAt() == null ? null : share.getExpiresAt().toString())
        .endedAt(share.getEndedAt() == null ? null : share.getEndedAt().toString())
        .invalidationReason(share.getInvalidationReason().orElse(null))
        .parties(ShareReference.of(share))
        .build();
  }
}
