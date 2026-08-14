package com.streamarr.server.services.auth;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record RenamePortableProfileCommand(UUID actingAccountId, UUID profileId, String name) {

  public RenamePortableProfileCommand {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(name, "name");
  }
}
