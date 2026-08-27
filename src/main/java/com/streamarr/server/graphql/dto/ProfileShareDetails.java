package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileShareDetails(
    UUID id,
    UUID profileId,
    UUID householdId,
    ProfileShareStatus status,
    boolean requiredByAccountMembership,
    String expiresAt,
    String endedAt,
    String invalidationReason) {

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
        .build();
  }
}
