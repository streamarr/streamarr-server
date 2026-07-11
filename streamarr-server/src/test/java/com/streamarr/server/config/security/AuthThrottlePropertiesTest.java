package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Auth Throttle Properties Tests")
class AuthThrottlePropertiesTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  @DisplayName("Should accept configuration when window is positive")
  void shouldAcceptConfigurationWhenWindowIsPositive() {
    var properties =
        AuthThrottleProperties.builder().maxAttempts(5).window(Duration.ofMinutes(15)).build();

    assertThat(VALIDATOR.validate(properties)).isEmpty();
  }

  @Test
  @DisplayName("Should reject configuration when window is zero")
  void shouldRejectConfigurationWhenWindowIsZero() {
    var properties = AuthThrottleProperties.builder().maxAttempts(5).window(Duration.ZERO).build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("window");
  }

  @Test
  @DisplayName("Should reject configuration when window is negative")
  void shouldRejectConfigurationWhenWindowIsNegative() {
    var properties =
        AuthThrottleProperties.builder().maxAttempts(5).window(Duration.ofSeconds(-1)).build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("window");
  }
}
