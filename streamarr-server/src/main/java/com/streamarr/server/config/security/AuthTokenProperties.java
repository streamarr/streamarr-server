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
    List<String> verificationKeys,
    // Bounded API staleness rides this ceiling (ADR 0016): revocation prevents renewal, and
    // access-token expiry bounds the residual authorization window.
    @NotNull @DurationMin(seconds = 0, inclusive = false) @DurationMax(minutes = 15)
        Duration accessTokenTtl,
    @NotNull @DurationMin(seconds = 0, inclusive = false) Duration refreshTokenTtl,
    @NotNull @DurationMin Duration rotationGrace) {

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
