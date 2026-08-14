package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.ProfileClassification;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfilePolicyChange(
    UUID actingAccountId,
    UUID profileId,
    ProfileClassification classification,
    Integer maximumAllowedRatingAge,
    String pinHash,
    boolean clearMaximumAllowedRatingAge,
    boolean clearPin) {

  public ProfilePolicyChange {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(profileId, "profileId");
  }

  public static class ProfilePolicyChangeBuilder {
    @Override
    public String toString() {
      return "ProfilePolicyChangeBuilder[REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "ProfilePolicyChange[actingAccountId=%s, profileId=%s, classification=%s, maximumAllowedRatingAge=%s, pinHash=<redacted>, clearMaximumAllowedRatingAge=%s, clearPin=%s]"
        .formatted(
            actingAccountId,
            profileId,
            classification,
            maximumAllowedRatingAge,
            clearMaximumAllowedRatingAge,
            clearPin);
  }
}
