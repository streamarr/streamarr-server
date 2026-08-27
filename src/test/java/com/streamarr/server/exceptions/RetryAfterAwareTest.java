package com.streamarr.server.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Retry After Aware Tests")
class RetryAfterAwareTest {

  @ParameterizedTest(name = "{0} -> {1}s")
  @MethodSource("delays")
  @DisplayName("Should round the retry delay up to whole seconds and never below one when reported")
  void shouldRoundRetryDelayUpToWholeSecondsAndNeverBelowOneWhenReported(
      Duration retryAfter, long expectedSeconds) {
    RetryAfterAware throttled = () -> retryAfter;

    assertThat(throttled.retryAfterSeconds()).isEqualTo(expectedSeconds);
  }

  private static Stream<Arguments> delays() {
    return Stream.of(
        Arguments.of(Duration.ofSeconds(-5), 1L),
        Arguments.of(Duration.ZERO, 1L),
        Arguments.of(Duration.ofMillis(1), 1L),
        Arguments.of(Duration.ofSeconds(42), 42L),
        Arguments.of(Duration.ofMillis(1500), 2L),
        Arguments.of(Duration.ofSeconds(Long.MAX_VALUE, 999_999_999L), Long.MAX_VALUE));
  }
}
