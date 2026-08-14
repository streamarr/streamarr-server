package com.streamarr.server.services.auth;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileManagerInvitationRejection(UUID actingAccountId, UUID invitationId) {

  public ProfileManagerInvitationRejection {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(invitationId, "invitationId");
  }
}
