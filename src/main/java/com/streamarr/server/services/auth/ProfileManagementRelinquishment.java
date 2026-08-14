package com.streamarr.server.services.auth;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileManagementRelinquishment(UUID actingAccountId, UUID profileId) {

  public ProfileManagementRelinquishment {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(profileId, "profileId");
  }
}
