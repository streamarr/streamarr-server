package com.streamarr.server.services.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Capped exponential backoff")
class CappedExponentialBackoffTest {

  @ParameterizedTest(name = "after {0} failures the delay is {1} seconds")
  @CsvSource({"0, 5", "1, 10", "2, 20", "5, 160", "6, 300", "7, 300", "100, 300"})
  @DisplayName("Should double the delay per failure until the cap when scheduling a retry")
  void shouldDoubleTheDelayPerFailureUntilTheCapWhenSchedulingARetry(
      int previousFailures, long seconds) {
    assertThat(CappedExponentialBackoff.delayAfter(previousFailures))
        .isEqualTo(Duration.ofSeconds(seconds));
  }
}
