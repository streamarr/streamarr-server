package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record HouseholdProfileRemoval(
    @NonNull AuthenticatedIdentity authority, @NonNull UUID shareId) {

  public UUID actingAccountId() {
    return authority.accountId();
  }
}
