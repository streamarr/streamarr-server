package com.streamarr.server.services.auth;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record RemoveProfileContentCeilingCommand(UUID actingAccountId, UUID profileId) {

  public RemoveProfileContentCeilingCommand {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(profileId, "profileId");
  }
}
