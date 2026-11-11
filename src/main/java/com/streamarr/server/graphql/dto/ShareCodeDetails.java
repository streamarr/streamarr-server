package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ShareCodeStatus;
import com.streamarr.server.services.identity.ShareCodeWithIssuer;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/** A share code as its Household's administrators see it; it never carries the code. */
@Builder
public record ShareCodeDetails(
    UUID id,
    UUID householdId,
    ShareCodeStatus status,
    String issuedAt,
    String expiresAt,
    String endedAt,
    UUID issuedByAccountId,
    String issuedByDisplayName) {

  public static ShareCodeDetails from(ShareCodeWithIssuer listed, Instant now) {
    var shareCode = listed.shareCode();
    return ShareCodeDetails.builder()
        .id(shareCode.getId())
        .householdId(shareCode.getHouseholdId())
        .status(shareCode.statusAt(now))
        .issuedAt(shareCode.getCreatedOn().toString())
        .expiresAt(shareCode.getExpiresAt().toString())
        .endedAt(shareCode.getEndedAt() == null ? null : shareCode.getEndedAt().toString())
        .issuedByAccountId(shareCode.getIssuerAccountId())
        .issuedByDisplayName(listed.issuedByDisplayName())
        .build();
  }
}
