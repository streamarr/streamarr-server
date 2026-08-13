package com.streamarr.server.fixtures;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import java.util.UUID;

public final class AuthenticatedIdentityFixture {

  private AuthenticatedIdentityFixture() {}

  public static AuthenticatedIdentity.AuthenticatedIdentityBuilder defaultIdentityBuilder() {
    return AuthenticatedIdentity.builder()
        .accountId(UUID.randomUUID())
        .role(AccountRole.USER)
        .authSessionId(UUID.randomUUID())
        .scope(TokenScope.PLAYBACK)
        .householdId(UUID.randomUUID())
        .profileId(UUID.randomUUID())
        .streamSessionId(UUID.randomUUID());
  }
}
