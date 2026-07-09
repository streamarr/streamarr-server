package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;
import org.springframework.security.oauth2.jwt.Jwt;

/** The token's identity claims, parsed once at authentication time. */
@Builder
public record AuthenticatedIdentity(
    UUID accountId,
    AccountRole role,
    UUID sessionId,
    Long sessionVersion,
    TokenScope scope,
    UUID householdId,
    HouseholdRole householdRole,
    Long membershipVersion,
    UUID profileId,
    Long policyVersion,
    UUID streamSessionId) {

  public AuthenticatedIdentity {
    Objects.requireNonNull(accountId, "accountId is required");
    Objects.requireNonNull(role, "role is required");
    Objects.requireNonNull(sessionId, "sessionId is required");
    Objects.requireNonNull(scope, "scope is required");

    if (scope == TokenScope.ACCOUNT
        && (householdId != null || householdRole != null || profileId != null)) {
      throw new IllegalArgumentException(
          "Account scope cannot carry household or profile identity");
    }
    if (scope != TokenScope.ACCOUNT && (householdId == null || householdRole == null)) {
      throw new IllegalArgumentException("Scoped identity requires household id and role");
    }
    if (scope == TokenScope.HOUSEHOLD && profileId != null) {
      throw new IllegalArgumentException("Household scope cannot carry profile identity");
    }
    if (scope == TokenScope.PROFILE && profileId == null) {
      throw new IllegalArgumentException("Profile scope requires profile identity");
    }
  }

  public static AuthenticatedIdentity fromJwt(Jwt jwt) {
    return AuthenticatedIdentity.builder()
        .accountId(UUID.fromString(jwt.getSubject()))
        .role(AccountRole.valueOf(jwt.getClaimAsString(TokenClaims.ROLE)))
        .sessionId(UUID.fromString(jwt.getClaimAsString(TokenClaims.SESSION_ID)))
        .sessionVersion(jwt.getClaim(TokenClaims.SESSION_VERSION))
        .scope(TokenScope.valueOf(jwt.getClaimAsString(TokenClaims.SCOPE).toUpperCase(Locale.ROOT)))
        .householdId(uuidClaim(jwt, TokenClaims.HOUSEHOLD_ID))
        .householdRole(householdRoleClaim(jwt))
        .membershipVersion(jwt.getClaim(TokenClaims.MEMBERSHIP_VERSION))
        .profileId(uuidClaim(jwt, TokenClaims.PROFILE_ID))
        .policyVersion(jwt.getClaim(TokenClaims.POLICY_VERSION))
        .streamSessionId(uuidClaim(jwt, TokenClaims.STREAM_SESSION))
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
