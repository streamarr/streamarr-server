package com.streamarr.server.services.auth;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileShareAcceptance(
    UUID actingAccountId, UUID shareId, UUID managementInvitationId) {

  public ProfileShareAcceptance {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(shareId, "shareId");
  }
}
