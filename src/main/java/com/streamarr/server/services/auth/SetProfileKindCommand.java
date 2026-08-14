package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.ProfileKind;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record SetProfileKindCommand(UUID actingAccountId, UUID profileId, ProfileKind kind) {

  public SetProfileKindCommand {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(kind, "kind");
  }
}
