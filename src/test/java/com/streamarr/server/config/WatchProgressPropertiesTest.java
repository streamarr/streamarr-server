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
  @DisplayName("Should accept positive values")
  void shouldAcceptPositiveValues() {
    var props = new WatchProgressProperties(10.0, 95.0, 600);

    assertThat(VALIDATOR.validate(props)).isEmpty();
    assertThat(props.minResumePercent()).isEqualTo(10.0);
    assertThat(props.maxResumePercent()).isEqualTo(95.0);
    assertThat(props.maxRemainingSeconds()).isEqualTo(600);
  }

  @Test
  @DisplayName("Should accept zero for minResumePercent")
  void shouldAcceptZeroForMinResumePercent() {
    var props = new WatchProgressProperties(0.0, 95.0, 600);

    assertThat(VALIDATOR.validate(props)).isEmpty();
    assertThat(props.minResumePercent()).isEqualTo(0.0);
  }

  @Test
  @DisplayName("Should accept zero for maxRemainingSeconds")
  void shouldAcceptZeroForMaxRemainingSeconds() {
    var props = new WatchProgressProperties(10.0, 95.0, 0);

    assertThat(VALIDATOR.validate(props)).isEmpty();
    assertThat(props.maxRemainingSeconds()).isEqualTo(0);
  }

  @Test
  @DisplayName("Should reject negative minResumePercent")
  void shouldRejectNegativeMinResumePercent() {
    var props = new WatchProgressProperties(-1.0, 95.0, 600);

    assertThat(VALIDATOR.validate(props)).isNotEmpty();
  }

  @Test
  @DisplayName("Should reject zero maxResumePercent")
  void shouldRejectZeroMaxResumePercent() {
    var props = new WatchProgressProperties(10.0, 0.0, 600);

    assertThat(VALIDATOR.validate(props)).isNotEmpty();
  }

  @Test
  @DisplayName("Should reject negative maxResumePercent")
  void shouldRejectNegativeMaxResumePercent() {
    var props = new WatchProgressProperties(10.0, -1.0, 600);

    assertThat(VALIDATOR.validate(props)).isNotEmpty();
  }

  @Test
  @DisplayName("Should reject negative maxRemainingSeconds")
  void shouldRejectNegativeMaxRemainingSeconds() {
    var props = new WatchProgressProperties(10.0, 95.0, -1);

    assertThat(VALIDATOR.validate(props)).isNotEmpty();
  }
}
