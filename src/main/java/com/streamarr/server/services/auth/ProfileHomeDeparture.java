package com.streamarr.server.services.auth;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileHomeDeparture(UUID actingAccountId, UUID activeProfileId) {

  public ProfileHomeDeparture {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(activeProfileId, "activeProfileId");
  }
}
