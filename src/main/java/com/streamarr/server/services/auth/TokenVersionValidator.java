package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Rejects tokens whose embedded version counters (sv/mv/pv) are stale. Runs inside the JwtDecoder,
 * so a stale token never becomes an Authentication. Unreadable counters reject — fail closed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenVersionValidator implements OAuth2TokenValidator<Jwt> {

  private final TokenVersionCache cache;

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    try {
      if (sessionCurrent(token) && membershipCurrent(token) && profileCurrent(token)) {
        return OAuth2TokenValidatorResult.success();
      }
      return staleTokenFailure();
    } catch (RuntimeException e) {
      log.warn(
          "Version counter lookup failed for account {} and session {}; rejecting token (fail closed).",
          token.getSubject(),
          token.getClaimAsString(TokenClaims.SESSION_ID),
          e);
      return staleTokenFailure();
    }
  }

  private boolean sessionCurrent(Jwt token) {
    var sessionId = token.getClaimAsString(TokenClaims.SESSION_ID);
    var sessionVersion = token.<Long>getClaim(TokenClaims.SESSION_VERSION);
    if (sessionId == null || sessionVersion == null) {
      return false;
    }

    return cache
        .sessionVersion(UUID.fromString(sessionId))
        .map(sessionVersion::equals)
        .orElse(false);
  }

  private boolean membershipCurrent(Jwt token) {
    var householdId = token.getClaimAsString(TokenClaims.HOUSEHOLD_ID);
    if (householdId == null) {
      return true;
    }

    var membershipVersion = token.<Long>getClaim(TokenClaims.MEMBERSHIP_VERSION);
    if (membershipVersion == null) {
      return false;
    }

    return cache
        .membershipVersion(UUID.fromString(token.getSubject()), UUID.fromString(householdId))
        .map(membershipVersion::equals)
        .orElse(false);
  }

  private boolean profileCurrent(Jwt token) {
    var profileId = token.getClaimAsString(TokenClaims.PROFILE_ID);
    if (profileId == null) {
      return true;
    }

    var policyVersion = token.<Long>getClaim(TokenClaims.POLICY_VERSION);
    if (policyVersion == null) {
      return false;
    }

    return cache
        .profilePolicyVersion(UUID.fromString(profileId))
        .map(policyVersion::equals)
        .orElse(false);
  }

  private static OAuth2TokenValidatorResult staleTokenFailure() {
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error("invalid_token", "Token version is stale.", null));
  }
}
