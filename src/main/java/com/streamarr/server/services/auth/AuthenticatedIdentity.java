package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import java.util.Locale;
import java.util.UUID;
import lombok.Builder;
import org.springframework.security.oauth2.jwt.Jwt;

/** The token's identity claims, parsed once at authentication time. */
@Builder
public record AuthenticatedIdentity(
    UUID accountId,
    AccountRole role,
    UUID sessionId,
    TokenScope scope,
    UUID householdId,
    HouseholdRole householdRole,
    UUID profileId) {

  public static AuthenticatedIdentity fromJwt(Jwt jwt) {
    return AuthenticatedIdentity.builder()
        .accountId(UUID.fromString(jwt.getSubject()))
        .role(AccountRole.valueOf(jwt.getClaimAsString(TokenClaims.ROLE)))
        .sessionId(UUID.fromString(jwt.getClaimAsString(TokenClaims.SESSION_ID)))
        .scope(TokenScope.valueOf(jwt.getClaimAsString(TokenClaims.SCOPE).toUpperCase(Locale.ROOT)))
        .householdId(uuidClaim(jwt, TokenClaims.HOUSEHOLD_ID))
        .householdRole(householdRoleClaim(jwt))
        .profileId(uuidClaim(jwt, TokenClaims.PROFILE_ID))
        .build();
  }

  private static UUID uuidClaim(Jwt jwt, String claim) {
    var value = jwt.getClaimAsString(claim);
    if (value == null) {
      return null;
    }
    return UUID.fromString(value);
  }

  private static HouseholdRole householdRoleClaim(Jwt jwt) {
    var value = jwt.getClaimAsString(TokenClaims.HOUSEHOLD_ROLE);
    if (value == null) {
      return null;
    }
    return HouseholdRole.valueOf(value);
  }
}
