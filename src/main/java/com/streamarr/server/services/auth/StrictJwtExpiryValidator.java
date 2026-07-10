package com.streamarr.server.services.auth;

import java.time.Clock;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects tokens without an expiry and tokens at or past it, with zero leeway: a token is valid
 * only while {@code now < exp}. RFC 7519 §4.1.4 makes {@code exp} optional; Streamarr makes it
 * mandatory because access-token expiry is the outer bound of the revocation model — the default
 * timestamp validator's clock skew and missing-{@code exp} tolerance would silently extend it.
 *
 * <p>The failure descriptions are load-bearing: {@code RestAuthenticationEntryPoint} maps
 * descriptions containing "expired" to EXPIRED_TOKEN (refresh-and-retry) and everything else to
 * INVALID_TOKEN (route to login), so only the past-expiry failure may mention expiry.
 */
public class StrictJwtExpiryValidator implements OAuth2TokenValidator<Jwt> {

  private final Clock clock;

  public StrictJwtExpiryValidator(Clock clock) {
    this.clock = clock;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    var expiresAt = token.getExpiresAt();
    if (expiresAt == null) {
      return failure("Token has no expiry claim.");
    }
    if (!clock.instant().isBefore(expiresAt)) {
      return failure("Token is expired.");
    }
    return OAuth2TokenValidatorResult.success();
  }

  private static OAuth2TokenValidatorResult failure(String description) {
    return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
  }
}
