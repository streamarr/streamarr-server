package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Session Progress Properties Tests")
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
  void shouldPassValidationWhenMinResumePercentIsZero() {
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
  void shouldFailValidationWhenMinResumePercentIsNegative() {
    var props = new WatchProgressProperties(-1.0, 95.0, 600);

    assertThat(VALIDATOR.validate(props)).isNotEmpty();
  }

  @Test
  @DisplayName("Should fail validation when maxPlayedPercent is zero")
  void shouldFailValidationWhenMaxResumePercentIsZero() {
    var props = new WatchProgressProperties(10.0, 0.0, 600);

    assertThat(VALIDATOR.validate(props)).isNotEmpty();
  }

  @Test
  @DisplayName("Should fail validation when maxPlayedPercent is negative")
  void shouldFailValidationWhenMaxResumePercentIsNegative() {
    var props = new WatchProgressProperties(10.0, -1.0, 600);

    assertThat(VALIDATOR.validate(props)).isNotEmpty();
  }

  @Test
  @DisplayName("Should fail validation when maxRemainingSeconds is negative")
  void shouldFailValidationWhenMaxRemainingSecondsIsNegative() {
    var props = new WatchProgressProperties(10.0, 95.0, -1);

    assertThat(VALIDATOR.validate(props)).isNotEmpty();
  }
}
