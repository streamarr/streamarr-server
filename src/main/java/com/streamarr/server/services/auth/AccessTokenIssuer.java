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
   * Mints a scope-change token whose lifetime is capped by the token that authorized the change:
   * {@code expiresAt = min(now + TTL, sourceExpiresAt)}. Selection changes context, never authority
   * — an uncapped reissue would let repeated selection extend access indefinitely. Only setup,
   * login, refresh, and successful password reauthentication start a fresh TTL.
   */
  public AccessToken issueDerived(TokenContext context, Instant sourceExpiresAt) {
    var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    var freshExpiry = now.plus(properties.accessTokenTtl());
    var cappedExpiry = sourceExpiresAt.isBefore(freshExpiry) ? sourceExpiresAt : freshExpiry;
    return mint(context, now, cappedExpiry);
  }

  private AccessToken mint(TokenContext context, Instant now, Instant expiresAt) {
    var scope = resolveScope(context);

    var claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .audience(List.of(properties.audience()))
            .id(UUID.randomUUID().toString())
            .subject(context.account().getId().toString())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim(TokenClaims.ROLES, List.of(context.account().getAccountRole().name()))
            .claim(TokenClaims.SESSION_ID, context.session().getId().toString())
            .claim(TokenClaims.SCOPE, scope.claimValue())
            .claim(TokenClaims.HOUSEHOLD_ID, context.account().getHomeHouseholdId().toString())
            .claim(TokenClaims.HOUSEHOLD_ROLE, context.account().getHouseholdRole().name());

    if (scope == TokenScope.PROFILE) {
      claims.claim(TokenClaims.PROFILE_ID, context.profileId().toString());
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

  private TokenScope resolveScope(TokenContext context) {
    if (context.profileId() != null) {
      return TokenScope.PROFILE;
    }

    return TokenScope.ACCOUNT;
  }
}
