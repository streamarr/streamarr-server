package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Watch Progress Properties Tests")
class WatchProgressPropertiesTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  @DisplayName("Should pass validation when all values are positive")
  void shouldPassValidationWhenAllValuesArePositive() {
    var props = new WatchProgressProperties(10.0, 95.0, 600);

    assertThat(VALIDATOR.validate(props)).isEmpty();
  }

  @Test
  @DisplayName("Should pass validation when minPlayedPercent is zero")
  void shouldPassValidationWhenMinPlayedPercentIsZero() {
    var props = new WatchProgressProperties(0.0, 95.0, 600);

    assertThat(VALIDATOR.validate(props)).isEmpty();
  }

  @Test
  @DisplayName("Should pass validation when maxRemainingSeconds is zero")
  void shouldPassValidationWhenMaxRemainingSecondsIsZero() {
    var props = new WatchProgressProperties(10.0, 95.0, 0);

    assertThat(VALIDATOR.validate(props)).isEmpty();
  }

  @Test
  @DisplayName("Should fail validation when minPlayedPercent is negative")
  void shouldFailValidationWhenMinPlayedPercentIsNegative() {
    var props = new WatchProgressProperties(-1.0, 95.0, 600);

    assertThat(VALIDATOR.validate(props))
        .extracting(v -> v.getPropertyPath().toString())
        .containsExactly("minPlayedPercent");
  }

  @Test
  @DisplayName("Should fail validation when maxPlayedPercent is zero")
  void shouldFailValidationWhenMaxPlayedPercentIsZero() {
    var props = new WatchProgressProperties(10.0, 0.0, 600);

    assertThat(VALIDATOR.validate(props))
        .extracting(v -> v.getPropertyPath().toString())
        .containsExactly("maxPlayedPercent");
  }

  @Test
  @DisplayName("Should fail validation when maxPlayedPercent is negative")
  void shouldFailValidationWhenMaxPlayedPercentIsNegative() {
    var props = new WatchProgressProperties(10.0, -1.0, 600);

    assertThat(VALIDATOR.validate(props))
        .extracting(v -> v.getPropertyPath().toString())
        .containsExactly("maxPlayedPercent");
  }

  @Test
  @DisplayName("Should fail validation when maxRemainingSeconds is negative")
  void shouldFailValidationWhenMaxRemainingSecondsIsNegative() {
    var props = new WatchProgressProperties(10.0, 95.0, -1);

    assertThat(VALIDATOR.validate(props))
        .extracting(v -> v.getPropertyPath().toString())
        .containsExactly("maxRemainingSeconds");
  }
}
