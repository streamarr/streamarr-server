package com.streamarr.server.services.auth;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileManagerInvite(UUID actingAccountId, UUID invitedAccountId, UUID profileId) {

  public ProfileManagerInvite {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(invitedAccountId, "invitedAccountId");
    Objects.requireNonNull(profileId, "profileId");
  }
}
