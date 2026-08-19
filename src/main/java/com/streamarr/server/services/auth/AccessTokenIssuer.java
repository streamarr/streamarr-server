package com.streamarr.server.services.auth;

import com.streamarr.server.config.security.AuthTokenProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Mints the signed snapshot (ADR 0024) from a validated {@link TokenContext}; it reads nothing from
 * the database. The identity services that build the context are responsible for the Household and
 * Profile having been checked live.
 */
@Service
@RequiredArgsConstructor
public class AccessTokenIssuer {

  private final JwtEncoder jwtEncoder;
  private final AuthTokenProperties properties;
  private final Clock clock;

  public AccessToken issue(TokenContext context) {
    // JWT timestamps carry whole seconds; truncate so expiresAt matches the encoded exp claim.
    var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    return mint(context, now, now.plus(properties.accessTokenTtl()));
  }

  /**
   * Mints a context-change token whose lifetime is capped by the token that authorized the change:
   * {@code expiresAt = min(now + TTL, sourceExpiresAt)}. Selection changes context, never authority
   * — an uncapped reissue would let repeated selection extend access indefinitely. Only setup,
   * login, and refresh start a fresh TTL.
   */
  public AccessToken issueDerived(TokenContext context, Instant sourceExpiresAt) {
    var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    var freshExpiry = now.plus(properties.accessTokenTtl());
    return mint(context, now, earlier(sourceExpiresAt, freshExpiry));
  }

  /**
   * Mints the reauthentication ceremony's replacement token (ADR 0024 §Fresh reauthentication): the
   * context is stamped {@code reauthenticated_at = now} and the token expires at the earlier of the
   * configured reauthentication window or the source token's expiry.
   */
  public AccessToken issueReauthenticated(TokenContext context, Instant sourceExpiresAt) {
    var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    var windowEnd = now.plus(properties.reauthenticationWindow());
    return mint(context.withReauthenticatedAt(now), now, earlier(sourceExpiresAt, windowEnd));
  }

  private static Instant earlier(Instant left, Instant right) {
    return left.isBefore(right) ? left : right;
  }

  private AccessToken mint(TokenContext context, Instant now, Instant expiresAt) {
    var scope = context.scope();
    var account = context.account();

    var claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .audience(List.of(properties.audience()))
            .id(UUID.randomUUID().toString())
            .subject(account.getId().toString())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim(TokenClaims.SESSION_ID, context.session().getId().toString())
            .claim(TokenClaims.SCOPE, scope.claimValue())
            .claim(TokenClaims.HOUSEHOLD_ID, account.getHouseholdId().toString())
            .claim(TokenClaims.HOUSEHOLD_ROLE, account.getHouseholdRole().name())
            .claim(TokenClaims.CONTEXT_HOUSEHOLD_ID, context.contextHouseholdId().toString());

    if (scope == TokenScope.PROFILE) {
      claims.claim(TokenClaims.PROFILE_ID, context.profileId().toString());
    }
    if (context.reauthenticatedAt() != null) {
      claims.claim(TokenClaims.REAUTHENTICATED_AT, context.reauthenticatedAt().getEpochSecond());
    }

    var jwt =
        jwtEncoder.encode(
            JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.ES256).build(), claims.build()));

    return AccessToken.builder()
        .value(jwt.getTokenValue())
        .expiresAt(expiresAt)
        .scope(scope)
        .build();
  }
}
