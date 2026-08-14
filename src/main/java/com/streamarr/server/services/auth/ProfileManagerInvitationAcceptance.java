package com.streamarr.server.services.auth;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileManagerInvitationAcceptance(UUID actingAccountId, UUID invitationId) {

  public ProfileManagerInvitationAcceptance {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(invitationId, "invitationId");
  }
}
