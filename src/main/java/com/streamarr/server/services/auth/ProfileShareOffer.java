package com.streamarr.server.services.auth;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileShareOffer(UUID actingAccountId, UUID profileId, UUID targetHouseholdId) {

  public ProfileShareOffer {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(targetHouseholdId, "targetHouseholdId");
  }
}
