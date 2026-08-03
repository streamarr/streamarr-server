package com.streamarr.server.config.health;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("TMDB Health Properties Tests")
class TmdbHealthPropertiesTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  @DisplayName("Should accept configuration when probe timeout is a short positive duration")
  void shouldAcceptConfigurationWhenProbeTimeoutIsShortPositiveDuration() {
    var properties = TmdbHealthProperties.builder().probeTimeout(Duration.ofSeconds(2)).build();

    assertThat(VALIDATOR.validate(properties)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  @DisplayName("Should reject configuration when probe timeout is not positive")
  void shouldRejectConfigurationWhenProbeTimeoutIsNotPositive(long timeoutSeconds) {
    var properties =
        TmdbHealthProperties.builder().probeTimeout(Duration.ofSeconds(timeoutSeconds)).build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("probeTimeout");
  }

  @Test
  @DisplayName("Should reject configuration when probe timeout exceeds ten seconds")
  void shouldRejectConfigurationWhenProbeTimeoutExceedsTenSeconds() {
    var properties = TmdbHealthProperties.builder().probeTimeout(Duration.ofSeconds(11)).build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("probeTimeout");
  }

  @Test
  @DisplayName("Should reject configuration when probe timeout is missing")
  void shouldRejectConfigurationWhenProbeTimeoutIsMissing() {
    var properties = TmdbHealthProperties.builder().build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("probeTimeout");
  }
}
