package com.streamarr.server.services.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Rejects malformed signed identity claims before they reach the authentication converter. */
@Slf4j
@Component
public class TokenIdentityValidator implements OAuth2TokenValidator<Jwt> {

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    try {
      AuthenticatedIdentity.fromJwt(token);
      return OAuth2TokenValidatorResult.success();
    } catch (RuntimeException e) {
      // Fail closed and loud: this signature-verified token came from our own issuer, so a
      // parse failure is a systemic issuer/parser mismatch that would silently reject the
      // whole fleet. The subject is signature-verified; the raw token is never logged.
      log.warn(
          "Identity claim validation failed for sub {}: rejecting token.", token.getSubject(), e);
      return invalidIdentityFailure();
    }
  }

  private static OAuth2TokenValidatorResult invalidIdentityFailure() {
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error("invalid_token", "Token identity claims are invalid.", null));
  }
}
