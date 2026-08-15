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

  /**
   * Issues an access token with an expiration time based on the configured access-token lifetime.
   *
   * @param context the token context containing account and session details
   * @return the signed access token
   */
  public AccessToken issue(TokenContext context) {
    // JWT timestamps carry whole seconds; truncate so expiresAt matches the encoded exp claim.
    var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    return mint(context, now, now.plus(properties.accessTokenTtl()));
  }

  /**
   * Issues a token with an expiration capped by the source token's expiration time.
   *
   * @param context the context used to create the token
   * @param sourceExpiresAt the expiration time of the source token
   * @return the issued access token
   */
  public AccessToken issueDerived(TokenContext context, Instant sourceExpiresAt) {
    var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    var freshExpiry = now.plus(properties.accessTokenTtl());
    var cappedExpiry = sourceExpiresAt.isBefore(freshExpiry) ? sourceExpiresAt : freshExpiry;
    return mint(context, now, cappedExpiry);
  }

  /**
   * Creates a signed access token containing account, session, scope, and expiration claims.
   *
   * @param context   the account and session context for the token
   * @param now       the token issue time
   * @param expiresAt the token expiration time
   * @return          the signed access token
   */
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
            .claim(TokenClaims.SCOPE, scope.claimValue());

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

  /**
   * Determines the token scope from the presence of a profile identifier.
   *
   * @param context the token context containing the optional profile identifier
   * @return {@code PROFILE} when a profile identifier is present; {@code ACCOUNT} otherwise
   */
  private TokenScope resolveScope(TokenContext context) {
    if (context.profileId() != null) {
      return TokenScope.PROFILE;
    }

    return TokenScope.ACCOUNT;
  }
}
