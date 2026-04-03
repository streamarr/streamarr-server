package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Session Progress Properties Tests")
class SessionProgressPropertiesTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  @DisplayName("Should pass validation when all values are positive")
  void shouldPassValidationWhenAllValuesArePositive() {
    var props = new SessionProgressProperties(10.0, 95.0, 600);

    assertThat(VALIDATOR.validate(props)).isEmpty();
  }

  @Test
  @DisplayName("Should pass validation when minResumePercent is zero")
  void shouldPassValidationWhenMinResumePercentIsZero() {
    var props = new SessionProgressProperties(0.0, 95.0, 600);

    assertThat(VALIDATOR.validate(props)).isEmpty();
  }

  @Test
  @DisplayName("Should pass validation when maxRemainingSeconds is zero")
  void shouldPassValidationWhenMaxRemainingSecondsIsZero() {
    var props = new SessionProgressProperties(10.0, 95.0, 0);

    assertThat(VALIDATOR.validate(props)).isEmpty();
  }

  @Test
  @DisplayName("Should fail validation when minResumePercent is negative")
  void shouldFailValidationWhenMinResumePercentIsNegative() {
    var props = new SessionProgressProperties(-1.0, 95.0, 600);

    assertThat(VALIDATOR.validate(props)).isNotEmpty();
  }

  @Test
  @DisplayName("Should fail validation when maxResumePercent is zero")
  void shouldFailValidationWhenMaxResumePercentIsZero() {
    var props = new SessionProgressProperties(10.0, 0.0, 600);

    assertThat(VALIDATOR.validate(props)).isNotEmpty();
  }

  @Test
  @DisplayName("Should fail validation when maxResumePercent is negative")
  void shouldFailValidationWhenMaxResumePercentIsNegative() {
    var props = new SessionProgressProperties(10.0, -1.0, 600);

    assertThat(VALIDATOR.validate(props)).isNotEmpty();
  }

  @Test
  @DisplayName("Should fail validation when maxRemainingSeconds is negative")
  void shouldFailValidationWhenMaxRemainingSecondsIsNegative() {
    var props = new SessionProgressProperties(10.0, 95.0, -1);

    assertThat(VALIDATOR.validate(props)).isNotEmpty();
  }
}
