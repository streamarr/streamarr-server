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
    var properties =
        TmdbHealthProperties.builder()
            .probeTimeout(Duration.ofSeconds(2))
            .cacheTtl(Duration.ofSeconds(30))
            .build();

    assertThat(VALIDATOR.validate(properties)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  @DisplayName("Should reject configuration when probe timeout is not positive")
  void shouldRejectConfigurationWhenProbeTimeoutIsNotPositive(long timeoutSeconds) {
    var properties =
        TmdbHealthProperties.builder()
            .probeTimeout(Duration.ofSeconds(timeoutSeconds))
            .cacheTtl(Duration.ofSeconds(30))
            .build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("probeTimeout");
  }

  @Test
  @DisplayName("Should reject configuration when probe timeout exceeds ten seconds")
  void shouldRejectConfigurationWhenProbeTimeoutExceedsTenSeconds() {
    var properties =
        TmdbHealthProperties.builder()
            .probeTimeout(Duration.ofSeconds(11))
            .cacheTtl(Duration.ofSeconds(30))
            .build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("probeTimeout");
  }

  @Test
  @DisplayName("Should reject configuration when probe timeout is missing")
  void shouldRejectConfigurationWhenProbeTimeoutIsMissing() {
    var properties = TmdbHealthProperties.builder().cacheTtl(Duration.ofSeconds(30)).build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("probeTimeout");
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  @DisplayName("Should reject configuration when cache TTL is not positive")
  void shouldRejectConfigurationWhenCacheTtlIsNotPositive(long ttlSeconds) {
    var properties =
        TmdbHealthProperties.builder()
            .probeTimeout(Duration.ofSeconds(2))
            .cacheTtl(Duration.ofSeconds(ttlSeconds))
            .build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("cacheTtl");
  }
}
