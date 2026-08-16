package com.streamarr.server.fixtures;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.UserAccount;
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

  public static AuthenticatedIdentity.AuthenticatedIdentityBuilder accountIdentityBuilder() {
    return AuthenticatedIdentity.builder()
        .accountId(UUID.randomUUID())
        .role(AccountRole.USER)
        .authSessionId(UUID.randomUUID())
        .scope(TokenScope.ACCOUNT)
        .householdId(UUID.randomUUID())
        .householdRole(HouseholdRole.MEMBER);
  }

  public static AuthenticatedIdentity accountIdentity(UserAccount account) {
    return accountIdentityBuilder()
        .accountId(account.getId())
        .role(account.getAccountRole())
        .householdId(account.getHomeHouseholdId())
        .householdRole(account.getHouseholdRole())
        .build();
  }

  public static AuthenticatedIdentity profileIdentity(UserAccount account, UUID profileId) {
    return accountIdentityBuilder()
        .accountId(account.getId())
        .role(account.getAccountRole())
        .scope(TokenScope.PROFILE)
        .householdId(account.getHomeHouseholdId())
        .householdRole(account.getHouseholdRole())
        .profileId(profileId)
        .build();
  }
}
