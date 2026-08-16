package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record ProfileHomeDeparture(@NonNull AuthenticatedIdentity authority) {

  public ProfileHomeDeparture {
    if (authority.scope() != TokenScope.PROFILE) {
      throw new IllegalArgumentException("Profile home departure requires profile authority");
    }
  }

  public UUID actingAccountId() {
    return authority.accountId();
  }

  public UUID activeProfileId() {
    return authority.profileId();
  }
}
