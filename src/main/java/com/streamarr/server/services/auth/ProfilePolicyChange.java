package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.ProfileClassification;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfilePolicyChange(
    UUID actingAccountId,
    UUID profileId,
    ProfileClassification classification,
    Integer maximumAllowedRatingAge,
    String pinHash) {

  @Override
  public String toString() {
    return "ProfilePolicyChange[actingAccountId=%s, profileId=%s, classification=%s, maximumAllowedRatingAge=%s, pinHash=<redacted>]"
        .formatted(actingAccountId, profileId, classification, maximumAllowedRatingAge);
  }
}
