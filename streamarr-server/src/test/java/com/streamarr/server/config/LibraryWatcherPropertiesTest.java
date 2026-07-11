package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Library Watcher Properties Tests")
class LibraryWatcherPropertiesTest {

  @Test
  @DisplayName("Should use defaults when values are zero or negative")
  void shouldUseDefaultsWhenValuesAreZeroOrNegative() {
    var properties = new LibraryWatcherProperties(0, -1, -5);

    assertThat(properties.stabilizationPeriodSeconds()).isEqualTo(30);
    assertThat(properties.pollIntervalSeconds()).isEqualTo(5);
    assertThat(properties.maxWaitSeconds()).isEqualTo(3600);
  }

  @Test
  @DisplayName("Should use provided values when positive")
  void shouldUseProvidedValuesWhenPositive() {
    var properties = new LibraryWatcherProperties(60, 10, 7200);

    assertThat(properties.stabilizationPeriodSeconds()).isEqualTo(60);
    assertThat(properties.pollIntervalSeconds()).isEqualTo(10);
    assertThat(properties.maxWaitSeconds()).isEqualTo(7200);
  }
}
