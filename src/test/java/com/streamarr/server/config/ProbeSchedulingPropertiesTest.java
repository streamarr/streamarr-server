package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Probe Scheduling Properties Tests")
class ProbeSchedulingPropertiesTest {

  @Test
  @DisplayName("Should retry busy workers after five seconds when no delay is configured")
  void shouldRetryBusyWorkersAfterFiveSecondsWhenNoDelayIsConfigured() {
    var properties = new ProbeSchedulingProperties(null);

    assertThat(properties.busyWorkerRetryDelay()).isEqualTo(Duration.ofSeconds(5));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  @DisplayName("Should reject the busy worker retry delay when it is not positive")
  void shouldRejectBusyWorkerRetryDelayWhenItIsNotPositive(long seconds) {
    var delay = Duration.ofSeconds(seconds);

    assertThatThrownBy(() -> new ProbeSchedulingProperties(delay))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Busy worker retry delay must be positive");
  }
}
