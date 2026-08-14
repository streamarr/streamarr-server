package com.streamarr.server.services.auth;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileShareCancellation(UUID actingAccountId, UUID shareId) {

  public ProfileShareCancellation {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(shareId, "shareId");
  }
}
