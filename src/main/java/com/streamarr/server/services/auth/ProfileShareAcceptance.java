package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record ProfileShareAcceptance(
    @NonNull AuthenticatedIdentity authority, @NonNull UUID shareId, UUID managementInvitationId) {

  public UUID actingAccountId() {
    return authority.accountId();
  }
}
