package com.streamarr.server.services.auth;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileShareRejection(UUID actingAccountId, UUID shareId) {

  public ProfileShareRejection {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(shareId, "shareId");
  }
}
