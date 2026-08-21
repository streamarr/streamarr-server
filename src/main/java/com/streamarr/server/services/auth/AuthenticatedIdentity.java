package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.exceptions.ProfileRequiredException;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The token's signed identity snapshot, parsed once at authentication time (ADR 0024): the Account
 * and its session, its membership Household and role, a ServerAdmin display fact (never authority —
 * the authorization module loads the live fact), the one context Household, the scope, and the
 * selected Profile.
 */
@Builder
public record AuthenticatedIdentity(
    @NonNull UUID accountId,
    @NonNull UUID authSessionId,
    @NonNull TokenScope scope,
    @NonNull UUID householdId,
    @NonNull HouseholdRole householdRole,
    boolean serverAdmin,
    @NonNull UUID contextHouseholdId,
    UUID profileId,
    UUID streamSessionId,
    Optional<Instant> reauthenticatedAt) {

  public AuthenticatedIdentity {
    if (reauthenticatedAt == null) {
      reauthenticatedAt = Optional.empty();
    }

    if (scope == TokenScope.ACCOUNT && profileId != null) {
      throw new IllegalArgumentException("Account scope cannot carry a selected profile");
    }
    if (scope != TokenScope.ACCOUNT && profileId == null) {
      throw new IllegalArgumentException("Profile and playback scope require a selected profile");
    }
    if (scope == TokenScope.PLAYBACK && streamSessionId == null) {
      throw new IllegalArgumentException("Playback scope requires a stream session");
    }
    if (scope != TokenScope.PLAYBACK && streamSessionId != null) {
      throw new IllegalArgumentException("Only playback scope can carry a stream session");
    }
  }

  public static AuthenticatedIdentity fromJwt(Jwt jwt) {
    return AuthenticatedIdentity.builder()
        .accountId(UUID.fromString(jwt.getSubject()))
        .authSessionId(UUID.fromString(jwt.getClaimAsString(TokenClaims.SESSION_ID)))
        .scope(TokenScope.valueOf(jwt.getClaimAsString(TokenClaims.SCOPE).toUpperCase(Locale.ROOT)))
        .householdId(UUID.fromString(jwt.getClaimAsString(TokenClaims.HOUSEHOLD_ID)))
        .householdRole(HouseholdRole.valueOf(jwt.getClaimAsString(TokenClaims.HOUSEHOLD_ROLE)))
        .serverAdmin(Boolean.TRUE.equals(jwt.getClaimAsBoolean(TokenClaims.SERVER_ADMIN)))
        .contextHouseholdId(UUID.fromString(jwt.getClaimAsString(TokenClaims.CONTEXT_HOUSEHOLD_ID)))
        .profileId(uuidClaim(jwt, TokenClaims.PROFILE_ID))
        .streamSessionId(uuidClaim(jwt, TokenClaims.STREAM_SESSION_ID))
        .reauthenticatedAt(
            Optional.ofNullable(jwt.getClaimAsInstant(TokenClaims.REAUTHENTICATED_AT)))
        .build();
  }

  /** The selected Profile in the context Household; absent in Account scope. */
  public PlaybackAuthority playbackAuthority() {
    if (profileId == null) {
      throw new ProfileRequiredException();
    }
    return PlaybackAuthority.builder()
        .authSessionId(authSessionId)
        .accountId(accountId)
        .householdId(contextHouseholdId)
        .profileId(profileId)
        .build();
  }

  private static UUID uuidClaim(Jwt jwt, String claim) {
    var value = jwt.getClaimAsString(claim);
    if (value == null) {
      return null;
    }
    return UUID.fromString(value);
  }
}
