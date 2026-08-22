package com.streamarr.server.fixtures;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import java.util.UUID;

public final class AuthenticatedIdentityFixture {

  private AuthenticatedIdentityFixture() {}

  /** A playback-scoped identity whose context Household is its membership Household. */
  public static AuthenticatedIdentity.AuthenticatedIdentityBuilder defaultIdentityBuilder() {
    var householdId = UUID.randomUUID();
    return AuthenticatedIdentity.builder()
        .accountId(UUID.randomUUID())
        .authSessionId(UUID.randomUUID())
        .scope(TokenScope.PLAYBACK)
        .householdId(householdId)
        .householdRole(HouseholdRole.MEMBER)
        .serverAdmin(false)
        .contextHouseholdId(householdId)
        .profileId(UUID.randomUUID())
        .streamSessionId(UUID.randomUUID());
  }

  /** An Account-scoped identity at the Profile picker of its membership Household. */
  public static AuthenticatedIdentity.AuthenticatedIdentityBuilder accountScopedBuilder() {
    return defaultIdentityBuilder().scope(TokenScope.ACCOUNT).profileId(null).streamSessionId(null);
  }

  /** A Profile-scoped identity in its membership Household. */
  public static AuthenticatedIdentity.AuthenticatedIdentityBuilder profileScopedBuilder() {
    return defaultIdentityBuilder().scope(TokenScope.PROFILE).streamSessionId(null);
  }
}
