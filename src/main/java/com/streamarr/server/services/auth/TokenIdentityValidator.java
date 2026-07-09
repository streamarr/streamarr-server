package com.streamarr.server.services.auth;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Rejects malformed signed identity claims before they reach the authentication converter. */
@Component
public class TokenIdentityValidator implements OAuth2TokenValidator<Jwt> {

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    try {
      AuthenticatedIdentity.fromJwt(token);
      return OAuth2TokenValidatorResult.success();
    } catch (RuntimeException _) {
      return invalidIdentityFailure();
    }
  }

  private static OAuth2TokenValidatorResult invalidIdentityFailure() {
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error("invalid_token", "Token identity claims are invalid.", null));
  }
}
