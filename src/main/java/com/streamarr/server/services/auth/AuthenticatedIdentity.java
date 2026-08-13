package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.ProfileRequiredException;
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
    UUID authSessionId,
    TokenScope scope,
    UUID householdId,
    UUID profileId,
    UUID streamSessionId) {

  public AuthenticatedIdentity {
    Objects.requireNonNull(accountId, "accountId is required");
    Objects.requireNonNull(role, "role is required");
    Objects.requireNonNull(authSessionId, "authSessionId is required");
    Objects.requireNonNull(scope, "scope is required");

    if (scope == TokenScope.ACCOUNT && profileId != null) {
      throw new IllegalArgumentException("Account scope cannot carry profile identity");
    }
    if (scope == TokenScope.PROFILE && profileId == null) {
      throw new IllegalArgumentException("Profile scope requires profile identity");
    }
    if (scope != TokenScope.PLAYBACK && householdId != null) {
      throw new IllegalArgumentException("Only playback scope can carry household identity");
    }
    if (scope == TokenScope.PLAYBACK
        && (householdId == null || profileId == null || streamSessionId == null)) {
      throw new IllegalArgumentException(
          "Playback scope requires profile identity and a stream session");
    }
    if (scope != TokenScope.PLAYBACK && streamSessionId != null) {
      throw new IllegalArgumentException("Only playback scope can carry a stream session");
    }
  }

  public static AuthenticatedIdentity fromJwt(Jwt jwt) {
    return AuthenticatedIdentity.builder()
        .accountId(UUID.fromString(jwt.getSubject()))
        .role(roleClaim(jwt))
        .authSessionId(UUID.fromString(jwt.getClaimAsString(TokenClaims.SESSION_ID)))
        .scope(TokenScope.valueOf(jwt.getClaimAsString(TokenClaims.SCOPE).toUpperCase(Locale.ROOT)))
        .householdId(uuidClaim(jwt, TokenClaims.HOUSEHOLD_ID))
        .profileId(uuidClaim(jwt, TokenClaims.PROFILE_ID))
        .streamSessionId(uuidClaim(jwt, TokenClaims.STREAM_SESSION_ID))
        .build();
  }

  public PlaybackAuthority playbackAuthority() {
    if (householdId == null || profileId == null) {
      throw new ProfileRequiredException();
    }
    return PlaybackAuthority.builder()
        .authSessionId(authSessionId)
        .accountId(accountId)
        .householdId(householdId)
        .profileId(profileId)
        .build();
  }

  private static AccountRole roleClaim(Jwt jwt) {
    var roles = jwt.getClaimAsStringList(TokenClaims.ROLES);
    if (roles == null || roles.isEmpty()) {
      throw new AuthenticationRequiredException();
    }
    return AccountRole.valueOf(roles.getFirst());
  }

  private static UUID uuidClaim(Jwt jwt, String claim) {
    var value = jwt.getClaimAsString(claim);
    if (value == null) {
      return null;
    }
    return UUID.fromString(value);
  }
}
