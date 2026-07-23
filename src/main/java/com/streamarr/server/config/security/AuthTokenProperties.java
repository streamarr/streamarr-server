package com.streamarr.server.config.security;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import lombok.Builder;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Builder
@Validated
@ConfigurationProperties(prefix = "auth.token")
public record AuthTokenProperties(
    String signingKey,
    // RFC 7519 iss claim; an HTTPS URL enables standard issuer-based JWKS resolution.
    String issuer,
    // RFC 9068 §2.2 aud claim: the resource identifier this server expects for itself.
    String audience,
    List<String> verificationKeys,
    // Bounded API staleness rides this ceiling (ADR 0016): revocation prevents renewal, and
    // access-token expiry bounds the residual authorization window.
    @NotNull @DurationMin(seconds = 0, inclusive = false) @DurationMax(minutes = 15)
        Duration accessTokenTtl,
    @NotNull @DurationMin(seconds = 0, inclusive = false) Duration refreshTokenTtl,
    @NotNull @DurationMin Duration rotationGrace) {

  private static final String DEFAULT_ISSUER = "streamarr";
  private static final String DEFAULT_AUDIENCE = "streamarr";

  public AuthTokenProperties {
    if (issuer == null || issuer.isBlank()) {
      issuer = DEFAULT_ISSUER;
    }
    if (audience == null || audience.isBlank()) {
      audience = DEFAULT_AUDIENCE;
    }
  }

  public static class AuthTokenPropertiesBuilder {

    @Override
    public String toString() {
      return "AuthTokenPropertiesBuilder[signingKey=[REDACTED], accessTokenTtl=%s, refreshTokenTtl=%s, rotationGrace=%s]"
          .formatted(accessTokenTtl, refreshTokenTtl, rotationGrace);
    }
  }

  @Override
  public String toString() {
    return "AuthTokenProperties[signingKey=[REDACTED], accessTokenTtl=%s, refreshTokenTtl=%s, rotationGrace=%s]"
        .formatted(accessTokenTtl, refreshTokenTtl, rotationGrace);
  }
}
