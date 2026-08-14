package com.streamarr.server.services.auth;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record HouseholdProfileRemoval(UUID actingAccountId, UUID shareId) {

  public HouseholdProfileRemoval {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(shareId, "shareId");
  }
}
