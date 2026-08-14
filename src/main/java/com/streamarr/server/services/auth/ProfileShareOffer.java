package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileShareOffer(UUID actingAccountId, UUID profileId, UUID targetHouseholdId) {

  public ProfileShareOffer {
    java.util.Objects.requireNonNull(actingAccountId, "actingAccountId");
    java.util.Objects.requireNonNull(profileId, "profileId");
    java.util.Objects.requireNonNull(targetHouseholdId, "targetHouseholdId");
  }
}
