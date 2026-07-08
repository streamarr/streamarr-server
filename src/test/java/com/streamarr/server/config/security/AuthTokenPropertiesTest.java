package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Auth Token Properties Tests")
class AuthTokenPropertiesTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  @DisplayName("Should accept configuration when all durations are valid")
  void shouldAcceptConfigurationWhenAllDurationsAreValid() {
    assertThat(VALIDATOR.validate(validProperties().build())).isEmpty();
  }

  @Test
  @DisplayName("Should accept configuration when rotation grace is zero")
  void shouldAcceptConfigurationWhenRotationGraceIsZero() {
    var properties = validProperties().rotationGrace(Duration.ZERO).build();

    assertThat(VALIDATOR.validate(properties)).isEmpty();
  }

  @Test
  @DisplayName("Should reject configuration when access token ttl is zero")
  void shouldRejectConfigurationWhenAccessTokenTtlIsZero() {
    var properties = validProperties().accessTokenTtl(Duration.ZERO).build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("accessTokenTtl");
  }

  @Test
  @DisplayName("Should reject configuration when refresh token ttl is negative")
  void shouldRejectConfigurationWhenRefreshTokenTtlIsNegative() {
    var properties = validProperties().refreshTokenTtl(Duration.ofDays(-1)).build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("refreshTokenTtl");
  }

  @Test
  @DisplayName("Should reject configuration when rotation grace is negative")
  void shouldRejectConfigurationWhenRotationGraceIsNegative() {
    var properties = validProperties().rotationGrace(Duration.ofSeconds(-1)).build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("rotationGrace");
  }

  private static AuthTokenProperties.AuthTokenPropertiesBuilder validProperties() {
    return AuthTokenProperties.builder()
        .signingKey("")
        .accessTokenTtl(Duration.ofMinutes(10))
        .refreshTokenTtl(Duration.ofDays(30))
        .rotationGrace(Duration.ofSeconds(30));
  }
}
