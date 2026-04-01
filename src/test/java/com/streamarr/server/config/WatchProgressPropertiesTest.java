package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Watch Progress Properties Tests")
class WatchProgressPropertiesTest {

  @Test
  @DisplayName("Should use provided values when all are positive")
  void shouldUseProvidedValuesWhenAllArePositive() {
    var props = new WatchProgressProperties(10.0, 95.0, 600);

    assertThat(props.minResumePercent()).isEqualTo(10.0);
    assertThat(props.maxResumePercent()).isEqualTo(95.0);
    assertThat(props.maxRemainingSeconds()).isEqualTo(600);
  }

  @Test
  @DisplayName("Should default minResumePercent when zero or negative")
  void shouldDefaultMinResumePercentWhenZeroOrNegative() {
    var props = new WatchProgressProperties(0.0, 95.0, 600);

    assertThat(props.minResumePercent()).isEqualTo(5.0);
  }

  @Test
  @DisplayName("Should default maxResumePercent when zero or negative")
  void shouldDefaultMaxResumePercentWhenZeroOrNegative() {
    var props = new WatchProgressProperties(10.0, -1.0, 600);

    assertThat(props.maxResumePercent()).isEqualTo(90.0);
  }

  @Test
  @DisplayName("Should default maxRemainingSeconds when zero or negative")
  void shouldDefaultMaxRemainingSecondsWhenZeroOrNegative() {
    var props = new WatchProgressProperties(10.0, 95.0, 0);

    assertThat(props.maxRemainingSeconds()).isEqualTo(300);
  }

  @Test
  @DisplayName("Should default all values when all are zero")
  void shouldDefaultAllValuesWhenAllAreZero() {
    var props = new WatchProgressProperties(0.0, 0.0, 0);

    assertThat(props.minResumePercent()).isEqualTo(5.0);
    assertThat(props.maxResumePercent()).isEqualTo(90.0);
    assertThat(props.maxRemainingSeconds()).isEqualTo(300);
  }
}
