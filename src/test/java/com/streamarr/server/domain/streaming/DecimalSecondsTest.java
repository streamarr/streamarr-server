package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Decimal Seconds Tests")
class DecimalSecondsTest {

  @Test
  @DisplayName("Should keep every nanosecond when a duration becomes decimal seconds")
  void shouldKeepEveryNanosecondWhenADurationBecomesDecimalSeconds() {
    var duration = Duration.ofSeconds(6, 6_000_001);

    assertThat(DecimalSeconds.of(duration)).isEqualTo(new BigDecimal("6.006000001"));
  }

  @ParameterizedTest
  @CsvSource({
    "9223372036, 854775808, 9223372036.854775808",
    "315576000000, 999999999, 315576000000.999999999",
    "9223372036854775807, 999999999, 9223372036854775807.999999999",
    "-9223372036854775808, 1, -9223372036854775807.999999999"
  })
  @DisplayName("Should keep exact decimal seconds when a duration's nanoseconds exceed a long")
  void shouldKeepExactDecimalSecondsWhenADurationsNanosecondsExceedALong(
      long seconds, int nanos, String expected) {
    var duration = Duration.ofSeconds(seconds, nanos);

    assertThat(DecimalSeconds.of(duration)).isEqualTo(new BigDecimal(expected));
  }

  @Test
  @DisplayName("Should reject a missing duration when converting it to decimal seconds")
  void shouldRejectAMissingDurationWhenConvertingItToDecimalSeconds() {
    assertThatNullPointerException().isThrownBy(() -> DecimalSeconds.of(null));
  }
}
